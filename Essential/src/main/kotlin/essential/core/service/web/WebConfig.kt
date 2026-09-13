package essential.core.service.web

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

internal fun generateSessionSecret(): String =
    ByteArray(48).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

@Serializable
data class WebConfig (
    @YamlComment("Port number for the web server")
    val port: Int = 32000,
    @YamlComment("Directory path where uploaded map files are stored")
    val uploadPath: String = "config/maps",
    @YamlComment("At least 32 characters of secret material used to encrypt and sign session cookies; generated on first load when left blank")
    // Blank, not a generated value. A default is an expression, and kotlinx.serialization evaluates it
    // every time it fills an absent key - so a config_web.yaml that lost this line minted a fresh
    // secret, which Config.load's migration re-save then wrote to disk, logging out every open
    // session. WebService.reloadConf mints it once instead, and says so when it does.
    val sessionSecret: String = "",
    @YamlComment("Only send session cookies over HTTPS")
    val secureCookie: Boolean = true,
    @YamlComment("Session validity duration in seconds (1 hour = 3600 seconds)")
    val sessionDuration: Long = 3600,
    @YamlComment("Maximum file upload size in bytes (10 MB = 10485760 bytes)")
    val maxFileSize: Long = 10485760,
    @YamlComment("Maximum requested width for generated map images")
    val maxImageWidth: Int = 2048,
    @YamlComment("Base URL of the map render server")
    val mapRenderServer: String = "https://api.mindustry-tool.com/api/v4/maps/image",
    @YamlComment("Discord server invitation URL shown to users who need to link their account")
    val discordUrl: String = "https://discord.gg/yourserver",
    @YamlComment("Enable WebSocket for real-time communication between web server and clients")
    val enableWebSocket: Boolean = true
)
