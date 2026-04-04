import re
import sys

file_path = 'Essential/src/main/kotlin/essential/core/CoreEvent.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# We need to truncate everything starting from the first "private fun addLog" 
# or if it's already a mess, starting from the end of "earnEXP" function.
match_exp_end = content.find("        target.level - oldLevel\n    )\n}")
if match_exp_end != -1:
    good_content = content[:match_exp_end + len("        target.level - oldLevel\n    )\n}")]
    
    end_content = '''

fun isUnitInside(target: Tile, first: Tile, second: Tile): Boolean {
    val minX = minOf(first.getX(), second.getX())
    val maxX = maxOf(first.getX(), second.getX())
    val minY = minOf(first.getY(), second.getY())
    val maxY = maxOf(first.getY(), second.getY())

    return target.getX() in minX..maxX && target.getY() in minY..maxY
}

/**
 * Calculate the MD5 hash of a map file
 * @param map The map to calculate the hash for
 * @return The MD5 hash of the map file as a hexadecimal string
 */
private fun calculateMapMD5Hash(map: Map): String {
    try {
        val data = Files.readAllBytes(map.file.file().toPath())
        val hash = MessageDigest.getInstance("MD5").digest(data)
        return BigInteger(1, hash).toString(16)
    } catch (e: NoSuchAlgorithmException) {
        Log.err("Failed to calculate MD5 hash: ${e.message}")
        return ""
    } catch (e: IOException) {
        Log.err("Failed to read map file: ${e.message}")
        return ""
    }
}

private fun checkValidBlock(tile: Tile): String {
    return if (tile.build != null && blockSelectRegex.matcher(tile.block().name).matches()) {
        (tile.build as ConstructBlock.ConstructBuild).current.name
    } else {
        tile.block().name
    }
}
'''
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(good_content + end_content)
    print("Successfully cleaned up the end of the file.")
else:
    print("Could not find the end of earnEXP function.")
