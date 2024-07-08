package essential.protect;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Config {
    public Pvp pvp;
    public Account account;
    public Protect protect;

    public static class Pvp {
        public Peace peace;
        public Border border;

        @JsonProperty("destroy-core")
        public boolean destroyCore;

        public static class Peace {
            public boolean enabled;
            public int time;
        }

        public static class Border {
            public boolean enabled;
        }
    }

    public static class Account {
        public enum AuthType {
            None, Password, Discord
        }
        public boolean enabled;
        @JsonProperty("auth-type")
        private String authType;
        @JsonProperty("discord-url")
        public String discordURL;
        public Boolean strict;

        public AuthType getAuthType() {
            return AuthType.valueOf(authType.substring(0, 1).toUpperCase() + authType.substring(1));
        }
    }

    public static class Protect {
        @JsonProperty("unbreakable-core")
        public boolean unbreakableCore;
        @JsonProperty("power-detect")
        public boolean powerDetect;
    }
}
