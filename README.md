package com.your.package.here; // <-- keep your existing package

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

// import your own classes
// import com.bofa.ecomm.mda.sep.mobilecto.config.HelperFunctions;
// import com.bofa.ecomm.mda.sep.mobilecto.config.AtomGlobalConfig;
// import com.bofa.ecomm.mda.sep.mobilecto.config.AtomGlobalConfigRepository;

public class AtomGlobalConfigService {

    private static final Logger LOGGER =
            Logger.getLogger(AtomGlobalConfigService.class.getName());

    // your existing fields / constructor / repository, etc.
    // private final AtomGlobalConfigRepository globalConfigRepository;

    /**
     * Old signature retained for backwards compatibility.
     * Calls the main method with no explicit profile (no JSON export).
     */
    public Map<String, Object> setGlobalConfigs() {
        return setGlobalConfigs(null);
    }

    /**
     * New environment-aware version.
     *
     * @param activeProfile the current environment/profile name, e.g.
     *                      "dev_local", "dev_mini", "prod_mini",
     *                      "prod_ext", "prod_local", "uat_local", etc.
     */
    public Map<String, Object> setGlobalConfigs(String activeProfile) {
        Map<String, Object> globalConfig = null;

        // 1. Try to load from DB
        try {
            List<AtomGlobalConfig> atomGlobalConfigList = this.getGlobalConfigs();

            if (atomGlobalConfigList != null && !atomGlobalConfigList.isEmpty()) {
                globalConfig = new HashMap<>(atomGlobalConfigList.size());
                for (AtomGlobalConfig cfg : atomGlobalConfigList) {
                    globalConfig.put(cfg.getKeyName(), cfg.getValue());
                }

                LOGGER.info("Loaded " + atomGlobalConfigList.size()
                        + " global configs from DB");

                // Decide if we should export JSON based on the profile
                Path exportPath = resolveExportPathForProfile(activeProfile);
                if (exportPath != null) {
                    exportConfigToJson(globalConfig, exportPath);
                }

                return globalConfig;
            }

            LOGGER.warning("Global config list from DB was empty");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Error loading global config from DB; will fall back to JSON", e);
        }

        // 2. Fallback: const_config.json from classpath/resources
        LOGGER.info("Falling back to const_config.json from resources");
        return loadConfigFromClasspathJson();
    }

    /**
     * Decide where (if at all) to export a JSON snapshot based on the profile.
     *
     * For dev_local, dev_mini, prod_mini:
     *   -> ${user.home}/atom-configs/const_config.json
     *
     * For prod_ext, prod_local, UATs or anything else:
     *   -> no export (returns null).
     */
    private Path resolveExportPathForProfile(String activeProfile) {
        if (activeProfile == null || activeProfile.isBlank()) {
            return null;
        }

        String profile = activeProfile.trim().toLowerCase();

        // Minis + dev_local: write snapshot under user.home/atom-configs
        if (profile.equals("dev_local")
                || profile.equals("dev_mini")
                || profile.equals("prod_mini")) {

            String userHome = System.getProperty("user.home");
            return Paths.get(userHome, "atom-configs", "const_config.json");
        }

        // prod_ext, prod_local, UAT variants, etc. -> no export
        if (profile.equals("prod_ext")
                || profile.equals("prod_local")
                || profile.startsWith("uat_")) {
            return null;
        }

        // Default: be conservative and do not export
        return null;
    }

    /**
     * Write config JSON to disk (used only in dev_local / dev_mini / prod_mini).
     * Errors here are logged but do NOT break the main flow.
     */
    private void exportConfigToJson(Map<String, Object> globalConfig, Path exportPath) {
        try {
            Files.createDirectories(exportPath.getParent());

            Gson gson = new GsonBuilder()
                    .setPrettyPrinting()
                    .create();

            String json = gson.toJson(globalConfig);

            if (!Files.exists(exportPath)) {
                Files.createFile(exportPath);
            }

            int result = HelperFunctions.writeToFile(exportPath.toString(), json);

            if (result == 1) {
                LOGGER.info("Exported global config to " + exportPath);
            } else {
                LOGGER.warning("HelperFunctions.writeToFile returned 0 for " + exportPath);
            }

        } catch (Exception e) {
            // Log but do not fail the request because of export
            LOGGER.log(Level.WARNING,
                    "Failed to export global config JSON to " + exportPath, e);
        }
    }

    /**
     * PROD-safe fallback: read const_config.json from classpath.
     * Requires file at src/main/resources/const_config.json.
     */
    private Map<String, Object> loadConfigFromClasspathJson() {
        String json = HelperFunctions.readFileFromResources("/const_config.json");
        if (json == null) {
            throw new IllegalStateException(
                    "Could not load global config from DB or const_config.json");
        }

        try {
            JsonElement jsonElement = JsonParser.parseString(json);
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            Map<String, Object> config = new Gson().fromJson(jsonObject, HashMap.class);
            LOGGER.info("Loaded global config from const_config.json");
            return config;
        } catch (JsonSyntaxException e) {
            LOGGER.log(Level.SEVERE, "Failed to parse const_config.json", e);
            throw new IllegalStateException("Invalid JSON in const_config.json", e);
        }
    }

    // your existing getGlobalConfigs() and other methods stay as they are
}
