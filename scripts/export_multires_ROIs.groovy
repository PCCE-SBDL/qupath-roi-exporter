/**
 * export_multires_ROIs.groovy
 *
 * For the CURRENTLY OPEN image in QuPath:
 *   1. Imports annotation polygons from a matching GeoJSON file
 *   2. Optionally replaces each annotation's shape with a new circular ROI
 *      of a fixed radius, centered on the original annotation's centroid
 *   3. Exports a crop for each (possibly re-shaped) annotation at 10x, 20x,
 *      and 40x resolution equivalents, optionally with the boundary drawn
 *
 * ---- ROI SHAPE (roiMode) ----
 *   'original' — use the annotation's own imported shape as-is
 *   'circle'   — ignore the original shape; build a new circle of radius
 *                `radiusMicrons` centered on the original centroid. Useful
 *                for standardizing region size/shape across annotations
 *                regardless of how the original polygon was drawn.
 *
 * ---- CROP MODE (cropMode) ----
 *   'annotation' — crop = the (possibly re-shaped) ROI's own bounding box.
 *                  Changing targetMPP only changes pixel density/detail of
 *                  that same fixed area — it will NOT look "more zoomed in".
 *   'fixed_fov'  — crop = a fixed physical square (in microns) centered on
 *                  the ROI's centroid, sized per magnification via
 *                  fovMicrons below. This gives a true zoom difference
 *                  between 10x/20x/40x, matching how a real microscope
 *                  objective works (smaller field of view at higher mag).
 *
 * Run via: Automate > Show script editor, paste this in, click Run
 * (with the target image open), OR run across a whole project with
 * "Run for project" (see notes at bottom of this file).
 */

import qupath.lib.io.GsonTools
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonParser
import java.awt.Color
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage
import javax.imageio.stream.FileImageOutputStream

// ---------------- CLI ARGUMENT PARSING ----------------
// When run via `QuPath script export_multires_ROIs.groovy -i <image> -a key=value ...`,
// QuPath populates a variable called `args` (List<String>) with one "key=value" string
// per -a flag. This block parses those into a map so the settings below can be
// overridden from the command line without editing this file. If run from the GUI
// script editor with no -a flags, `args` is empty/absent and all defaults apply as-is.
def cliArgs = [:]
try {
    if (args != null) {
        args.each { a ->
            def parts = a.toString().split('=', 2)
            if (parts.length == 2) cliArgs[parts[0]] = parts[1]
        }
    }
} catch (MissingPropertyException e) {
    // `args` doesn't exist at all (e.g. running from the GUI script editor) — fine, use defaults
}
def getArg = { key, defaultVal -> cliArgs.containsKey(key) ? cliArgs[key] : defaultVal }

// ---------------- USER SETTINGS ----------------
// Each of these can be left as-is (edited directly here) OR overridden from the
// command line with `-a key=value`, e.g. `-a geojson=/path/to/file.geojson`
def geojsonPath = getArg('geojson', "/path/to/annotations/annotations.geojson")
def outputDir   = getArg('output', "/path/to/output_folder")

// ---- ROI shape ----
def roiMode = getArg('roiMode', 'circle')                              // 'original' or 'circle'
def radiusMicrons = (getArg('radiusMicrons', '250.0') as Double)       // used if roiMode == 'circle'
def addCircleToHierarchy = getArg('addCircleToHierarchy', 'false').toBoolean()

// ---- Crop mode ----
def cropMode = getArg('cropMode', 'fixed_fov')   // 'annotation' or 'fixed_fov'

// Which magnifications to export, e.g. "-a magnifications=10,40" to export only those two.
// Any magnification value works — MPP and FOV are computed from formulas below,
// not looked up from a fixed table, so there's no list to keep in sync.
def requestedMags = getArg('magnifications', '10,20,40,60,100,200').split(',').collect { it.trim() as Integer }

// Reference constants the formulas are built from:
//   MPP(mag)  = mppReferenceConstant / mag   -- matches 0.25 um/px at 40x by default (10/40 = 0.25)
//   FOV(mag)  = fovReferenceConstant / mag   -- matches 500um FOV at 40x by default (20000/40 = 500)
// Override these via -a if your reference calibration differs.
def mppReferenceConstant = (getArg('mppReferenceConstant', '10.0') as Double)
def fovReferenceConstant = (getArg('fovReferenceConstant', '20000.0') as Double)

def targetMPP = requestedMags.collectEntries { mag -> [mag, mppReferenceConstant / mag] }
def fovMicrons = requestedMags.collectEntries { mag -> [mag, fovReferenceConstant / mag] }

def imageFormat = getArg('format', 'png')   // "png" (lossless), "tif" (lossless), or "jpg" (lossy, smaller files)

// ---- Output quality (see notes above imageFormat) ----
def jpegQuality       = (getArg('jpegQuality', '0.95') as Double)   // 0.0-1.0, only used if imageFormat == "jpg"
def tiffCompression   = getArg('tiffCompression', 'LZW')            // "LZW", "Deflate", or "NONE"; only used if imageFormat == "tif"
def pngCompressionLevel = (getArg('pngCompressionLevel', '6') as Integer)  // 0-9; only used if imageFormat == "png", no effect on quality

// ---- Boundary overlay ----
def drawBoundary     = getArg('drawBoundary', 'true').toBoolean()
def boundaryColor    = new Color(255, 255, 0)   // yellow
def boundaryWidthPx  = (getArg('boundaryWidthPx', '3.0') as Double)

// ---- Scale bar ----
def addScaleBar         = getArg('addScaleBar', 'true').toBoolean()
def scaleBarPosition    = getArg('scaleBarPosition', 'bottom-right')   // 'bottom-left' or 'bottom-right'
def scaleBarColor       = new Color(255, 255, 255)                     // white bar + label
def scaleBarMaxFraction = (getArg('scaleBarMaxFraction', '0.25') as Double)  // bar length caps at this fraction of image width
def scaleBarMarginPx    = (getArg('scaleBarMarginPx', '20') as Double)
def scaleBarThicknessPx = (getArg('scaleBarThicknessPx', '8') as Double)
def scaleBarFontSize    = (getArg('scaleBarFontSize', '24') as Double)
// -------------------------------------------------

def imageData = getCurrentImageData()
def server = imageData.getServer()
def hierarchy = imageData.getHierarchy()

// 1. Import GeoJSON annotations into the current image
def gsonFile = new File(geojsonPath)
if (!gsonFile.exists()) {
    println "ERROR: GeoJSON not found at ${geojsonPath}"
    return
}
def gson = GsonTools.getInstance()
def type = new TypeToken<List<PathObject>>() {}.getType()

// GeoJSON can either be a bare array of features, or a FeatureCollection
// object wrapping a "features" array (the common case) — handle both.
def parsedJson = JsonParser.parseString(gsonFile.text)
def featureElement = (parsedJson.isJsonObject() && parsedJson.getAsJsonObject().has("features")) ?
    parsedJson.getAsJsonObject().get("features") : parsedJson

List<PathObject> annotations = gson.fromJson(featureElement, type)
hierarchy.addObjects(annotations)
fireHierarchyUpdate()
println "Imported ${annotations.size()} annotations from ${geojsonPath}"

// 2. Prepare output directory
mkdirs(outputDir)

// 3. Get a clean image name for file naming
def imageName = getProjectEntry() != null ? getProjectEntry().getImageName() : server.getMetadata().getName()
imageName = imageName.replaceAll("\\.ome\\.tif+\$", "").replaceAll("[^a-zA-Z0-9_-]", "_")

// 4. Get the image's native pixel size (µm/px at downsample=1)
def currentMPP = server.getPixelCalibration().getAveragedPixelSizeMicrons()
if (currentMPP == null || currentMPP.isNaN()) {
    println "ERROR: This image has no pixel size (MPP) metadata — cannot compute magnification-equivalent downsamples."
    println "Set pixel calibration manually (Image tab > double-click pixel size) or hardcode a downsample instead."
    return
}
println "Native pixel size: ${currentMPP} um/px"
println "ROI mode: ${roiMode}   Crop mode: ${cropMode}"

// Helper: build a new circular ROI centered on an existing ROI's centroid
def buildCircleROI = { origRoi, radiusUm, mpp ->
    double cx = origRoi.getCentroidX()
    double cy = origRoi.getCentroidY()
    double rPx = radiusUm / mpp
    def plane = origRoi.getImagePlane()
    return ROIs.createEllipseROI(cx - rPx, cy - rPx, rPx * 2, rPx * 2, plane)
}

// Helper: write a BufferedImage with explicit quality/compression settings
def writeImageWithQuality = { img, path, format ->
    def writers = ImageIO.getImageWritersByFormatName(format)
    if (!writers.hasNext()) {
        // Fallback: no ImageWriteParam support for this format, just write plainly
        ImageIO.write(img, format, new File(path))
        return
    }
    def writer = writers.next()
    def param = writer.getDefaultWriteParam()

    if (format.equalsIgnoreCase("jpg") || format.equalsIgnoreCase("jpeg")) {
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
        param.setCompressionQuality((float) jpegQuality)
    } else if (format.equalsIgnoreCase("tif") || format.equalsIgnoreCase("tiff")) {
        if (param.canWriteCompressed()) {
            if (tiffCompression == "NONE") {
                param.setCompressionMode(ImageWriteParam.MODE_DISABLED)
            } else {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
                param.setCompressionType(tiffCompression)   // "LZW" or "Deflate"
            }
        }
    } else if (format.equalsIgnoreCase("png")) {
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
            param.setCompressionQuality((float) (1.0f - (pngCompressionLevel / 9.0f)))  // PNG plugin uses quality inverted vs level
        }
    }

    def file = new File(path)
    def ios = new FileImageOutputStream(file)
    writer.setOutput(ios)
    writer.write(null, new IIOImage(img, null, null), param)
    writer.dispose()
    ios.close()
}

// Helper: draw a scale bar with auto-selected round length onto the bottom of an image
def drawScaleBar = { g2d, imgWidthPx, imgHeightPx, outputMPP ->
    // Candidate "nice" round lengths in microns, largest-first
    def niceLengthsUm = [10000, 5000, 2000, 1000, 500, 250, 200, 150, 100, 75, 50, 25, 20, 10, 5, 2, 1]

    double maxBarPx = imgWidthPx * scaleBarMaxFraction
    double maxBarUm = maxBarPx * outputMPP
    double barLengthUm = niceLengthsUm.find { it <= maxBarUm } ?: niceLengthsUm.last()
    double barLengthPx = barLengthUm / outputMPP

    double margin = scaleBarMarginPx
    double barHeight = scaleBarThicknessPx
    double x0 = (scaleBarPosition == 'bottom-left') ? margin : (imgWidthPx - margin - barLengthPx)
    double y0 = imgHeightPx - margin - barHeight

    // Bar: filled with a thin black outline so it reads on both light and dark tissue
    g2d.setColor(Color.BLACK)
    g2d.fillRect((int) x0 - 1, (int) y0 - 1, (int) Math.round(barLengthPx) + 2, (int) barHeight + 2)
    g2d.setColor(scaleBarColor)
    g2d.fillRect((int) x0, (int) y0, (int) Math.round(barLengthPx), (int) barHeight)

    // Label, drawn with a black shadow offset so it stays readable on any background
    def label = (barLengthUm >= 1000) ? "${(barLengthUm / 1000)} mm" : "${(int) barLengthUm} um"
    g2d.setFont(g2d.getFont().deriveFont((float) scaleBarFontSize))
    def fm = g2d.getFontMetrics()
    double textWidth = fm.stringWidth(label)
    double textX = x0 + (barLengthPx - textWidth) / 2
    double textY = y0 - 6

    g2d.setColor(Color.BLACK)
    g2d.drawString(label, (float) (textX + 1), (float) (textY + 1))
    g2d.setColor(scaleBarColor)
    g2d.drawString(label, (float) textX, (float) textY)
}

// 5. Loop through annotations, export a crop at each target resolution
def allAnnotations = hierarchy.getAnnotationObjects()
def targetKeys = (cropMode == 'fixed_fov') ? fovMicrons.keySet() : targetMPP.keySet()
println "Exporting ${allAnnotations.size()} annotations at: ${targetKeys}x"

allAnnotations.eachWithIndex { annotation, idx ->
    def originalRoi = annotation.getROI()
    if (originalRoi == null) return

    // Re-shape ROI if requested — everything downstream (crop + boundary) uses exportRoi
    def exportRoi = originalRoi
    if (roiMode == 'circle') {
        exportRoi = buildCircleROI(originalRoi, radiusMicrons, currentMPP)
        if (addCircleToHierarchy) {
            def circleAnnotation = PathObjects.createAnnotationObject(exportRoi, annotation.getPathClass())
            hierarchy.addObject(circleAnnotation)
        }
    }

    def annotationName = annotation.getName()
    def label = annotationName != null ? annotationName :
        ((annotation.getPathClass() != null ? annotation.getPathClass().toString() : "annotation") + "_${idx}")
    label = label.replaceAll("[^a-zA-Z0-9_-]", "_")

    targetKeys.each { mag ->

        RegionRequest request
        double downsample

        if (cropMode == 'fixed_fov') {
            downsample = targetMPP[mag] / currentMPP

            double fovPx = fovMicrons[mag] / currentMPP
            double cx = exportRoi.getCentroidX()
            double cy = exportRoi.getCentroidY()
            double x = cx - fovPx / 2
            double y = cy - fovPx / 2

            x = Math.max(0, Math.min(x, server.getWidth() - fovPx))
            y = Math.max(0, Math.min(y, server.getHeight() - fovPx))

            request = RegionRequest.createInstance(
                server.getPath(), downsample,
                (int) Math.round(x), (int) Math.round(y),
                (int) Math.round(fovPx), (int) Math.round(fovPx)
            )
        } else {
            // 'annotation' mode: crop = exportRoi's own bounding box (circle or original shape)
            downsample = targetMPP[mag] / currentMPP
            request = RegionRequest.createInstance(server.getPath(), downsample, exportRoi)
        }

        def fileName = "${imageName}_${label}_${mag}x.${imageFormat}"
        def outputPath = buildFilePath(outputDir, fileName)

        try {
            def img = server.readRegion(request)

            if (drawBoundary || addScaleBar) {
                def g2d = img.createGraphics()
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                if (drawBoundary) {
                    g2d.setColor(boundaryColor)
                    g2d.setStroke(new BasicStroke((float) boundaryWidthPx))

                    def transform = new AffineTransform()
                    transform.scale(1.0 / request.getDownsample(), 1.0 / request.getDownsample())
                    transform.translate(-request.getX(), -request.getY())

                    def shape = exportRoi.getShape()
                    def transformedShape = transform.createTransformedShape(shape)
                    g2d.draw(transformedShape)
                }

                if (addScaleBar) {
                    double outputMPP = currentMPP * request.getDownsample()
                    drawScaleBar(g2d, img.getWidth(), img.getHeight(), outputMPP)
                }

                g2d.dispose()
            }

            writeImageWithQuality(img, outputPath, imageFormat)
            println "  wrote ${fileName}  (downsample=${downsample.round(3)}, FOV=${cropMode == 'fixed_fov' ? fovMicrons[mag] + 'um' : 'ROI bbox'}, roi=${roiMode})"
        } catch (Exception e) {
            println "  FAILED ${fileName}: ${e.getMessage()}"
        }
    }
}

if (roiMode == 'circle' && addCircleToHierarchy) {
    fireHierarchyUpdate()
}

println "Done."

/*
 * ---------------- BATCH PROCESSING NOTES ----------------
 *
 * To run this across MANY images automatically:
 *
 * 1. Create a QuPath project (File > Project > Create project) and add all
 *    your .ome.tiff files (File > Project > Add images).
 *
 * 2. Put each image's GeoJSON somewhere findable by a consistent naming
 *    convention, and adjust geojsonPath above to build the path from the
 *    current project entry, e.g.:
 *      def geojsonPath = "/path/to/annotations/" +
 *          getProjectEntry().getImageName().replaceAll("\\.ome\\.tiff?\$", "") + ".geojson"
 *
 * 3. In QuPath: Automate > Show script editor > paste this script >
 *    Run > "Run for project" > select all images.
 *
 * 4. For headless/command-line batch runs on macOS:
 *      /Applications/QuPath.app/Contents/MacOS/QuPath script \
 *          export_multires_ROIs.groovy \
 *          -p /path/to/your_project.qpproj
 *    Run `QuPath script --help` for exact flags for your installed version.
 */
