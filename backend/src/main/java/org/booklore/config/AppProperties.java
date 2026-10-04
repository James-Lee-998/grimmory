package org.booklore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {
    private String pathConfig;
    private String bookdropFolder;
    private String version;
    private RemoteAuth remoteAuth;
    private OutboundRequests outbound;
    private OIDC oidc;
    private VirtualBookSeed virtualBookSeed = new VirtualBookSeed();
    private Translation translation = new Translation();

    /**
     * Type of disk storage where library files are stored.
     * Defaults to LOCAL. Set to NETWORK if using NFS, SMB/CIFS, or other network-mounted storage.
     * Some features like file move/reorganization are disabled on network storage due to
     * unreliable atomic operations that can cause data corruption or loss.
     */
    private String diskType = "LOCAL";

    public boolean isLocalStorage() {
        return "LOCAL".equalsIgnoreCase(diskType);
    }

    @Getter
    @Setter
    public static class RemoteAuth {
        private boolean enabled;
        private boolean createNewUsers;
        private String headerName;
        private String headerUser;
        private String headerEmail;
        private String headerGroups;
        private String adminGroup;
        private String groupsDelimiter = "\\s+";  // Default to whitespace for backward compatibility
    }

    @Getter
    @Setter
    public static class OutboundRequests {
        private int connectTimeout = 15;
        private int readTimeout = 15;
        private List<String> restrictedRanges = List.of();
    }

    @Getter
    @Setter
    public static class VirtualBookSeed {
        private boolean enabled = true;
        /**
         * Spring resource location of the Project Gutenberg catalog CSV (pg_catalog.csv), e.g. classpath:... or file:/path/books.csv.
         * Files ending in .gz are decompressed on the fly.
         */
        private String location = "classpath:seed/gutenberg-catalog.csv.gz";
    }

    @Getter
    @Setter
    public static class Translation {
        /** Base URL of a LibreTranslate server, e.g. http://libretranslate:5000. Translation is off when blank. */
        private String libretranslateUrl;
        /** Optional LibreTranslate API key, for servers started with --api-keys. Never sent to the browser. */
        private String libretranslateApiKey;
        private int maxTextLength = 2000;
    }

    @Getter
    @Setter
    public static class OIDC {
        private Boolean forceDisable = false;
        private Boolean allowUnsafeHosts = false;
    }
}
