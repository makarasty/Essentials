package essential.core.service.protect

import arc.util.Log

private val addressLine = Regex("^[0-9a-fA-F.:/]+$")

class PluginData {
    /**
     * Setting this rebuilds [vpnMatchers] once: the connect path used to construct a matcher per
     * entry per connection, and the list is tens of thousands of lines long. Lines that are not an
     * address are rejected before construction, because parsing one costs a name service lookup.
     */
    var vpnList: Array<String> = arrayOf()
        set(value) {
            field = value
            vpnMatchers = value
                .filter { addressLine.matches(it) }
                .mapNotNull { runCatching { IpAddressMatcher(it) }.getOrNull() }
            // A body served as HTML, or with CRLF line endings, drops every entry at once and leaves
            // the rule matching nothing while looking healthy.
            if (vpnMatchers.size < value.size) {
                Log.warn("[EssentialProtect] ${value.size - vpnMatchers.size} of ${value.size} VPN list entries were not usable")
            }
        }

    @Volatile
    var vpnMatchers: List<IpAddressMatcher> = emptyList()
        private set
}
