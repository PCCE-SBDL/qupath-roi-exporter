/**
 * crop_sample_ome_tiff.groovy
 *
 * Crops a fixed pixel-region subset out of the CURRENTLY OPEN image and
 * writes it back out as a smaller pyramidal OME-TIFF, preserving pixel
 * calibration (MPP) metadata. Intended for generating small sample/demo
 * data out of a large whole-slide OME-TIFF.
 *
 * Run via:
 *   QuPath script crop_sample_ome_tiff.groovy -i <input.ome.tif> \
 *       -a output=<output.ome.tif> -a width=5000 -a height=5000
 *
 * If -a x / -a y are omitted, the crop is centered on the full image.
 *
 * By default the crop is written with lossless compression. Pass
 * -a compression=jpeg to use lossy JPEG compression instead (QuPath's
 * default JPEG quality — not independently configurable via this API),
 * for a much smaller file. Useful for sample/demo data that needs to fit
 * under a repo host's file-size limits.
 */

import qupath.lib.images.writers.ome.OMEPyramidWriter
import qupath.lib.regions.RegionRequest

// ---------------- CLI ARGUMENT PARSING ----------------
def cliArgs = [:]
try {
    if (args != null) {
        args.each { a ->
            def parts = a.toString().split('=', 2)
            if (parts.length == 2) cliArgs[parts[0]] = parts[1]
        }
    }
} catch (MissingPropertyException e) {
    // running from GUI script editor with no -a flags — fine, use defaults
}
def getArg = { key, defaultVal -> cliArgs.containsKey(key) ? cliArgs[key] : defaultVal }

// ---------------- USER SETTINGS ----------------
def outputPath = getArg('output', '/path/to/sample_crop.ome.tif')
def cropWidth  = (getArg('width', '5000') as Integer)
def cropHeight = (getArg('height', '5000') as Integer)
def xArg = getArg('x', null)   // top-left x in full-resolution pixels; null = center
def yArg = getArg('y', null)   // top-left y in full-resolution pixels; null = center
def tileSize = (getArg('tileSize', '512') as Integer)
def compression = getArg('compression', 'lossless')   // 'lossless' or 'jpeg'
// -------------------------------------------------

def imageData = getCurrentImageData()
def server = imageData.getServer()

int fullW = server.getWidth()
int fullH = server.getHeight()

int x = (xArg != null) ? (xArg as Integer) : Math.max(0, (int) ((fullW - cropWidth) / 2))
int y = (yArg != null) ? (yArg as Integer) : Math.max(0, (int) ((fullH - cropHeight) / 2))

// Clamp so the requested box never runs off the edge of the image
cropWidth  = Math.min(cropWidth, fullW - x)
cropHeight = Math.min(cropHeight, fullH - y)

println "Full image size: ${fullW} x ${fullH}"
println "Cropping region: x=${x}, y=${y}, width=${cropWidth}, height=${cropHeight}"

def request = RegionRequest.createInstance(server.getPath(), 1.0, x, y, cropWidth, cropHeight)

def builder = new OMEPyramidWriter.Builder(server)
        .region(request)
        .tileSize(tileSize)
        .parallelize()

if (compression == 'jpeg') {
    builder = builder.compression(OMEPyramidWriter.CompressionType.JPEG)
    println "Using lossy JPEG compression"
} else {
    builder = builder.losslessCompression()
}

builder.build().writePyramid(outputPath)

println "Wrote cropped OME-TIFF to ${outputPath}"
