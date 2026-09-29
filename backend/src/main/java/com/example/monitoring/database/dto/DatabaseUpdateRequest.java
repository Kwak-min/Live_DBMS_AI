package com.example.monitoring.database.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
public class DatabaseUpdateRequest {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Current positive configVersion; mismatch returns 409")
    private Long configVersion;
    @Schema(description = "New display name, trimmed; 1..100 Unicode code points") private String name;
    @Schema(description = "New DNS name or IP; disallowed destinations are rejected") private String host;
    @Schema(description = "New allowed port, 1..65535") private Integer port;
    @Schema(nullable = true, description = "Explicit null clears the database name") private String databaseName;
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, description = "New username only; never returned") private String username;
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, description = "New password only; never returned") private String password;
    @Schema(description = "Enable or pause scheduled collection") private Boolean enabled;

    @Schema(hidden = true)
    private boolean nameSpecified;
    @Schema(hidden = true)
    private boolean hostSpecified;
    @Schema(hidden = true)
    private boolean portSpecified;
    @Schema(hidden = true)
    private boolean databaseNameSpecified;
    @Schema(hidden = true)
    private boolean usernameSpecified;
    @Schema(hidden = true)
    private boolean passwordSpecified;
    @Schema(hidden = true)
    private boolean enabledSpecified;

    @JsonSetter("configVersion") public void setConfigVersion(Long value) { this.configVersion = value; }
    @JsonSetter("name") public void setName(String value) { this.name = value; this.nameSpecified = true; }
    @JsonSetter("host") public void setHost(String value) { this.host = value; this.hostSpecified = true; }
    @JsonSetter("port") public void setPort(Integer value) { this.port = value; this.portSpecified = true; }
    @JsonSetter("databaseName") public void setDatabaseName(String value) { this.databaseName = value; this.databaseNameSpecified = true; }
    @JsonSetter("username") public void setUsername(String value) { this.username = value; this.usernameSpecified = true; }
    @JsonSetter("password") public void setPassword(String value) { this.password = value; this.passwordSpecified = true; }
    @JsonSetter("enabled") public void setEnabled(Boolean value) { this.enabled = value; this.enabledSpecified = true; }

    public boolean hasChanges() {
        return nameSpecified || hostSpecified || portSpecified || databaseNameSpecified
                || usernameSpecified || passwordSpecified || enabledSpecified;
    }
}
