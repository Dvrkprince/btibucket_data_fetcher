@Service
public class AtomGlobalConfigService {

    private static final Logger LOGGER =
            Logger.getLogger(AtomGlobalConfigService.class.getName());

    private final Environment environment;

    @Autowired
    public AtomGlobalConfigService(Environment environment) {
        this.environment = environment;
    }

    public Map<String, Object> setGlobalConfigs() {
        Map<String, Object> globalConfig = null;

        // 1. Try DB first
        try {
            List<AtomGlobalConfig> atomGlobalConfigList = this.getGlobalConfigs();

            if (atomGlobalConfigList != null && !atomGlobalConfigList.isEmpty()) {
                globalConfig = new HashMap<>(atomGlobalConfigList.size());
                for (AtomGlobalConfig cfg : atomGlobalConfigList) {
                    globalConfig.put(cfg.getKeyName(), cfg.getValue());
                }

                LOGGER.info("Loaded " + atomGlobalConfigList.size()
                        + " global configs from DB");

                // ✅ For certain environments, write a JSON snapshot
                Path exportPath = resolveExportPathForCurrentEnv();
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
     * Decide where (if at all) to export a JSON snapshot based on environment.
     */
    private Path resolveExportPathForCurrentEnv() {
        // Minis: write to user.home/atom-configs/const_config.json
        if (environment.acceptsProfiles("dev_mini", "prod_mini")) {
            String userHome = System.getProperty("user.home");
            return Paths.get(userHome, "atom-configs", "const_config.json");
        }

        // dev_local: OK to write, same location as minis
        if (environment.acceptsProfiles("dev_local")) {
            String userHome = System.getProperty("user.home");
            return Paths.get(userHome, "atom-configs", "const_config.json");
        }

        // prod_ext, prod_local, UATs, etc. → no export
        if (environment.acceptsProfiles("prod_ext", "prod_local",
                                        "uat_local", "uat_mini", "uat_ext")) {
            return null;
        }

        // Default: be conservative – no export
        return null;
    }

    /**
     * Write config JSON to disk (used only in dev_local / *_mini).
     */
    private void exportConfigToJson(Map<String, Object> globalConfig, Path exportPath) {
        try {
            Files.createDirectories(exportPath.getParent());

            String json = new GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                    .toJson(globalConfig);

            // If you want to keep using your HelperFunctions:
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
            // IMPORTANT: log it but DO NOT fail the request because of export
            LOGGER.log(Level.WARNING,
                    "Failed to export global config JSON to " + exportPath, e);
        }
    }

    /**
     * PROD-safe fallback: read const_config.json from classpath.
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
}
