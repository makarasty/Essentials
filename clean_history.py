import re
import sys

file_path = 'Essential/src/main/kotlin/essential/core/CoreEvent.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Remove addLog from withdraw
content = re.sub(r'\s*addLog\(\s*TileLog\(\s*System\.currentTimeMillis\(\),\s*event\.player\.name,\s*"withdraw".*?event\.tile\.config\(\)\s*\)\s*\)', '', content, flags=re.DOTALL)

# 2. Remove addLog from deposit
content = re.sub(r'\s*addLog\(\s*TileLog\(\s*System\.currentTimeMillis\(\),\s*event\.player\.name,\s*"deposit".*?event\.tile\.config\(\)\s*\)\s*\)', '', content, flags=re.DOTALL)

# 3. Remove addLog from config
content = re.sub(r'\s*addLog\(\s*TileLog\(\s*System\.currentTimeMillis\(\),\s*event\.player\.name,\s*"config".*?event\.value\s*\)\s*\)', '', content, flags=re.DOTALL)
content = re.sub(r'\s*addLog\(\s*TileLog\(\s*System\.currentTimeMillis\(\),\s*event\.player\.name,\s*"message".*?event\.value\s*\)\s*\)', '', content, flags=re.DOTALL)

# 4. Remove addLog from tap
content = re.sub(r'\s*addLog\(\s*TileLog\(\s*System\.currentTimeMillis\(\),\s*event\.player\.name,\s*"tap".*?null\s*\)\s*\)', '', content, flags=re.DOTALL)

# 5. Fix blockBuildEnd
# Replace the whole block from 'val buf = ArrayList<TileLog>()' to 'if (!Vars.state.rules.infiniteResources && event.tile != null...'
# with just the if condition
block_build_end_pattern = r'\s*val\s*buf\s*=\s*ArrayList<TileLog>\(\)\s*scope\.launch\s*\{\s*try\s*\{.*?catch\s*\(e:\s*Exception\)\s*\{\s*Log\.err\("Error retrieving world history from database",\s*e\)\s*\}\s*if\s*\(!Vars\.state\.rules\.infiniteResources\s*&&\s*event\.tile\s*!=\s*null\s*&&\s*event\.tile\.build\s*!=\s*null\s*&&\s*event\.tile\.build\.maxHealth\(\)\s*==\s*event\.tile\.block\(\)\.health\.toFloat\(\)\s*&&\s*\(!buf\.isEmpty\(\)\s*&&\s*buf\.last\(\)\.tile\s*!=\s*event\.tile\.block\(\)\.name\)\)\s*\{\s*target\.blockPlaceCount\+\+\s*target\.exp\s*\+=\s*blockExp\[block\.name\]!!\s*target\.currentExp\s*\+=\s*blockExp\[block\.name\]!!\s*\}\s*\}'
content = re.sub(block_build_end_pattern, '\n\n                if (!Vars.state.rules.infiniteResources && event.tile != null && event.tile.build != null && event.tile.build.maxHealth() == event.tile.block().health.toFloat()) {\n                    target.blockPlaceCount++\n                    target.exp += blockExp[block.name]!!\n                    target.currentExp += blockExp[block.name]!!\n                }', content, flags=re.DOTALL)

# 6. Remove addLog from blockBuildEnd place
content = re.sub(r'\s*addLog\(\s*TileLog\(\s*System\.currentTimeMillis\(\),\s*target\.name,\s*"place".*?event\.config\s*\)\s*\)', '', content, flags=re.DOTALL)

# 7. Remove addLog from blockBuildEnd break
content = re.sub(r'\s*addLog\(\s*TileLog\(\s*System\.currentTimeMillis\(\),\s*target\.name,\s*"break".*?event\.config\s*\)\s*\)', '', content, flags=re.DOTALL)

# 8. Remove addLog from buildSelect
content = re.sub(r'\s*addLog\(\s*TileLog\(\s*System\.currentTimeMillis\(\),\s*\(event\.builder\s*as\s*Playerc\)\.plainName\(\),\s*"select".*?event\.tile\.build\.config\(\)\s*\)\s*\)', '', content, flags=re.DOTALL)

# 9. Clean up any remaining addLog / TileLog classes at the end.
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
    content = good_content + end_content

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Re-cleaned CoreEvent.kt completely.')
