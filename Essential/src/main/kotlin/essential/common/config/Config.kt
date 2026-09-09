package essential.common.config

import arc.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import essential.common.bundle
import essential.common.rootPath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths


object Config {
    val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    init {
        renameConfigsDirectory()
    }

    fun renameConfigsDirectory() {
        val oldDir = rootPath.child("configs")
        val newDir = rootPath.child("config")
        if (oldDir.exists() && !newDir.exists()) {
            try {
                // renameTo reports failure by returning false, not by throwing, and on Windows an open
                // handle is enough to fail it. Unread, the next step writes fresh defaults and logs
                // config.created as though this were a first run.
                if (!oldDir.file().renameTo(newDir.file())) {
                    Log.err(bundle["config.migrate.failed", oldDir.absolutePath(), newDir.absolutePath()])
                }
            } catch (e: Exception) {
                Log.err(e)
            }
        }
    }

    fun hasMissingKeys(userNode: YamlNode, canonicalNode: YamlNode): Boolean {
        if (userNode is YamlMap && canonicalNode is YamlMap) {
            val userKeys = userNode.entries.keys.map { it.content }.toSet()
            for ((keyNode, canonicalValue) in canonicalNode.entries) {
                val key = keyNode.content
                if (key !in userKeys) {
                    return true
                }
                val userValue = userNode.entries.entries.find { it.key.content == key }?.value
                if (userValue == null) {
                    return true
                }
                if (hasMissingKeys(userValue, canonicalValue)) {
                    return true
                }
            }
        } else if (canonicalNode is YamlMap) {
            return true
        }
        return false
    }

    /**
     * Check whether the user config file is missing any comment line that the canonical
     * (freshly serialized) content carries. Used to upgrade older comment-less config files
     * to the documented format on every startup.
     */
    fun hasMissingComments(userContent: String, canonicalContent: String): Boolean {
        val userComments = userContent.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") }
            .toSet()
        return canonicalContent.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") }
            .any { it !in userComments }
    }

    /**
     * Keys the user's file carries that the canonical content does not. [hasMissingKeys] only walks the
     * canonical side, so these are exactly the lines the migration re-save drops — a mistyped key among
     * them, and the operator loses both the setting and the evidence of it.
     */
    fun extraKeys(userNode: YamlNode, canonicalNode: YamlNode, path: String = ""): List<String> {
        if (userNode !is YamlMap || canonicalNode !is YamlMap) return emptyList()
        val canonical = canonicalNode.entries.entries.associate { it.key.content to it.value }
        return userNode.entries.entries.flatMap { (keyNode, userValue) ->
            val key = keyNode.content
            val full = if (path.isEmpty()) key else "$path.$key"
            val canonicalValue = canonical[key]
            if (canonicalValue == null) listOf(full) else extraKeys(userValue, canonicalValue, full)
        }
    }

    /**
     * Comment lines the user wrote that the canonical content does not carry. [hasMissingComments] asks
     * only the opposite question, so these are silently lost by the same re-save.
     */
    fun extraComments(userContent: String, canonicalContent: String): List<String> {
        val canonicalComments = canonicalContent.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") }
            .toSet()
        return userContent.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") }
            .filter { it !in canonicalComments }
            .toList()
    }

    /**
     * Load configuration from a YAML file.
     *
     * @param name YAML file name in the config folder
     * @param serializer Serializable configuration class
     * @param defaultConfig Default configuration when the file does not exist
     * @return Returns the configuration when loaded successfully, otherwise null
     */
    inline fun <reified T> load(
        name: String,
        serializer: KSerializer<T>,
        defaultConfig: T? = null
    ): T? {
        val name = "$name.yaml"
        val file = rootPath.child("config/$name").file()

        if (!file.exists()) {
            if (defaultConfig != null) {
                try {
                    rootPath.child("config").mkdirs()

                    save(name, serializer, defaultConfig)
                    Log.info(bundle["config.created", name])
                    return defaultConfig
                } catch (e: IOException) {
                    Log.err(bundle["config.create.failed", name], e)
                    return null
                }
            } else {
                Log.warn(bundle["config.not.found", name])
                return null
            }
        }

        return try {
            val content = Files.readString(Paths.get(rootPath.child("config/$name").absolutePath()))
            val config = yaml.decodeFromString(serializer, content)
            try {
                val userNode = yaml.parseToYamlNode(content)
                val canonicalContent = yaml.encodeToString(serializer, config)
                val canonicalNode = yaml.parseToYamlNode(canonicalContent)
                // Re-save when keys are missing (migration) or when the canonical comments
                // are absent from the user file (upgrade older comment-less configs).
                // strictMode is off, so the parser drops a mistyped key without complaint and the
                // operator never learns the setting does nothing. Report it whether or not a re-save
                // is due: a fully migrated file gets no rewrite and would otherwise stay silent.
                val unknownKeys = extraKeys(userNode, canonicalNode)
                if (unknownKeys.isNotEmpty()) {
                    Log.warn(bundle["config.unknown.keys", name, unknownKeys.joinToString(", ")])
                }
                if (hasMissingKeys(userNode, canonicalNode) || hasMissingComments(content, canonicalContent)) {
                    // The re-save writes the whole file from the parsed object, so the comments,
                    // ordering and quoting in it go with it.
                    val lostComments = extraComments(content, canonicalContent)
                    if (lostComments.isNotEmpty()) {
                        Log.warn(bundle["config.rewrite.comments", name, lostComments.size.toString()])
                    }
                    save(name, serializer, config)
                }
            } catch (e: Exception) {
                Log.err("Error migrating config $name: ${e.message}")
            }
            config
        } catch (e: IOException) {
            Log.err(bundle["config.load.failed", name], e)
            null
        } catch (e: SerializationException) {
            Log.err(bundle["config.parse.failed", name], e)
            null
        }
    }

    /**
     * Save the configuration as YAML.
     *
     * @param name YAML file name in the config folder
     * @param serializer Serializable configuration class
     * @param config Configuration object to save
     * @return true if saved successfully, otherwise false
     */
    fun <T> save(name: String, serializer: KSerializer<T>, config: T): Boolean {
        val file = rootPath.child("config/${name}")

        return try {
            val content = yaml.encodeToString(serializer, config)
            file.writeString(content, false)
            Log.info(bundle["config.saved", name])
            true
        } catch (e: IOException) {
            Log.err(bundle["config.save.failed", name], e)
            false
        } catch (e: SerializationException) {
            Log.err(bundle["config.serialize.failed", name], e)
            false
        }
    }
}