import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BitbucketTestDataCrawler {

  // ---- CONFIG ----
  static final String HOST = "https://<your-bitbucket-host>"; // no trailing slash
  static final String PROJECT_KEY = "MOBAUTOMAT";
  static final String BRANCH = "develop"; // or "main" or a tag/commit
  static final String TOKEN = "<PAT or Bearer token>";
  static final int PAGE_LIMIT = 100;
  static final int THREADS = 8;

  // For path discovery:
  static final String ROOT_PATH = "src/test/java/com/bofa/mda/handsets";

  // ---- HTTP + JSON ----
  final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build();
  final ObjectMapper mapper = new ObjectMapper();
  final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

  record TestDataMethod(String repo, String feature, String className, String methodName,
                        Map<String,String> annotationAttrs, String path) {}
  // feature -> methods
  final Map<String, List<TestDataMethod>> byFeature = new ConcurrentHashMap<>();

  public static void main(String[] args) throws Exception {
    BitbucketTestDataCrawler app = new BitbucketTestDataCrawler();
    app.run();
  }

  void run() throws Exception {
    try {
      List<JsonNode> repos = listAutomationRepos();
      List<Callable<Void>> tasks = new ArrayList<>();

      for (JsonNode repo : repos) {
        String repoSlug = repo.get("slug").asText();
        tasks.add(() -> {
          crawlRepo(repoSlug);
          return null;
        });
      }
      pool.invokeAll(tasks);
    } finally {
      pool.shutdown();
    }

    // Example: print grouped results
    byFeature.forEach((feature, items) -> {
      System.out.println("Feature: " + feature);
      for (TestDataMethod tdm : items) {
        System.out.println("  [" + tdm.repo + "] " + tdm.className + "#" + tdm.methodName + " (" + tdm.path + ")");
        if (!tdm.annotationAttrs.isEmpty()) {
          System.out.println("    @TestData attrs: " + tdm.annotationAttrs);
        }
      }
    });
  }

  // 1) List repos and filter startsWith("automation_")
  List<JsonNode> listAutomationRepos() throws Exception {
    List<JsonNode> results = new ArrayList<>();
    Integer start = 0;
    while (true) {
      String url = String.format(
          "%s/rest/api/latest/repos?projectKey=%s&name=automation_&limit=%d&start=%d",
          HOST, encode(PROJECT_KEY), PAGE_LIMIT, start
      );
      JsonNode root = getJson(url);
      for (JsonNode repo : root.withArray("values")) {
        String name = repo.get("name").asText();
        if (name.startsWith("automation_")) {
          results.add(repo);
        }
      }
      if (root.path("isLastPage").asBoolean(true)) break;
      start = root.path("nextPageStart").asInt();
    }
    return results;
  }

  void crawlRepo(String repoSlug) throws Exception {
    // 2) Walk from ROOT_PATH; find any .../<feature>/test directories and enumerate java files beneath
    List<String> testDirs = new ArrayList<>();
    browseForTestDirs(PROJECT_KEY, repoSlug, ROOT_PATH, testDirs);

    // For each test dir, collect .java files (recursively)
    List<String> javaFiles = new ArrayList<>();
    for (String testDir : testDirs) {
      listJavaFilesRecursive(PROJECT_KEY, repoSlug, testDir, javaFiles);
    }

    // 3/4) For each java file: fetch raw -> cheap contains -> parse -> extract
    List<Callable<Void>> fileTasks = new ArrayList<>();
    for (String path : javaFiles) {
      fileTasks.add(() -> {
        processJavaFile(repoSlug, path);
        return null;
      });
    }
    pool.invokeAll(fileTasks);
  }

  void browseForTestDirs(String projectKey, String repoSlug, String path, List<String> out) throws Exception {
    // Browse current path
    JsonNode page = browse(projectKey, repoSlug, path);
    JsonNode children = page.path("children").path("values");
    if (!children.isArray()) return;

    for (JsonNode child : children) {
      String type = child.path("type").asText();
      String childPath = child.path("path").path("toString").asText();
      String name = child.path("path").path("components").isArray()
          ? last(child.path("path").path("components"))
          : leaf(childPath);

      if ("DIRECTORY".equals(type)) {
        if ("test".equals(name)) {
          out.add(childPath); // this is .../<feature>/test
        }
        // Continue recursion to find deeper .../test directories
        browseForTestDirs(projectKey, repoSlug, childPath, out);
      }
    }
  }

  void listJavaFilesRecursive(String projectKey, String repoSlug, String dir, List<String> out) throws Exception {
    Integer start = null;
    while (true) {
      String base = String.format("%s/rest/api/latest/projects/%s/repos/%s/browse/%s?at=%s",
          HOST, encode(projectKey), encode(repoSlug), encode(dir), encode(BRANCH));
      String url = (start == null) ? base : (base + "&start=" + start);
      JsonNode page = getJson(url);
      JsonNode children = page.path("children").path("values");
      if (children.isArray()) {
        for (JsonNode child : children) {
          String type = child.path("type").asText();
          String childPath = child.path("path").path("toString").asText();
          if ("DIRECTORY".equals(type)) {
            listJavaFilesRecursive(projectKey, repoSlug, childPath, out);
          } else if ("FILE".equals(type) && childPath.endsWith(".java")) {
            out.add(childPath);
          }
        }
      }
      if (page.path("children").path("isLastPage").asBoolean(true)) break;
      start = page.path("children").path("nextPageStart").asInt();
    }
  }

  void processJavaFile(String repoSlug, String path) throws Exception {
    String raw = getRaw(PROJECT_KEY, repoSlug, path);
    if (raw == null || !raw.contains("@TestData")) return;

    // Parse & verify package contains both required segments
    CompilationUnit cu;
    try {
      cu = StaticJavaParser.parse(raw);
    } catch (Exception e) {
      // ignore malformed
      return;
    }
    String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    if (!pkg.contains("com.bofa.mda.handsets") || !pkg.contains("test")) return;

    String className = cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
        .map(c -> c.getNameAsString()).orElse(leaf(path).replace(".java",""));

    // featureName = segment immediately before final 'test' in the path
    String feature = featureFromPath(path);

    // Extract methods with @TestData
    List<TestDataMethod> methods = cu.findAll(MethodDeclaration.class).stream()
        .filter(m -> m.getAnnotations().stream().anyMatch(a -> {
          String n = a.getNameAsString();
          return n.equals("TestData") || n.endsWith(".TestData");
        }))
        .map(m -> new TestDataMethod(
            repoSlug,
            feature,
            className,
            m.getNameAsString(),
            extractAnnotationAttrs(m),
            path
        ))
        .collect(Collectors.toList());

    if (!methods.isEmpty()) {
      byFeature.computeIfAbsent(feature, k -> Collections.synchronizedList(new ArrayList<>()))
               .addAll(methods);
    }
  }

  Map<String,String> extractAnnotationAttrs(MethodDeclaration m) {
    return m.getAnnotations().stream()
        .filter(a -> {
          String n = a.getNameAsString();
          return n.equals("TestData") || n.endsWith(".TestData");
        })
        .findFirst()
        .map(a -> {
          Map<String,String> map = new LinkedHashMap<>();
          if (a instanceof NormalAnnotationExpr nae) {
            for (MemberValuePair p : nae.getPairs()) {
              map.put(p.getNameAsString(), p.getValue().toString());
            }
          } else if (a instanceof SingleMemberAnnotationExpr smae) {
            map.put("value", smae.getMemberValue().toString());
          } else {
            // marker annotation: no attributes
          }
          return map;
        })
        .orElseGet(Collections::emptyMap);
  }

  // ---- Helpers ----

  JsonNode browse(String projectKey, String repoSlug, String path) throws Exception {
    String url = String.format(
        "%s/rest/api/latest/projects/%s/repos/%s/browse/%s?at=%s",
        HOST, encode(projectKey), encode(repoSlug), encode(path), encode(BRANCH)
    );
    return getJson(url);
  }

  String getRaw(String projectKey, String repoSlug, String path) throws Exception {
    String url = String.format(
        "%s/rest/api/latest/projects/%s/repos/%s/raw/%s?at=%s",
        HOST, encode(projectKey), encode(repoSlug), encode(path), encode(BRANCH)
    );
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + TOKEN)
        .GET().build();
    HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    if (resp.statusCode() == 200) {
      return new String(resp.body(), StandardCharsets.UTF_8);
    }
    return null;
  }

  JsonNode getJson(String url) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + TOKEN)
        .header("Accept", "application/json")
        .GET().build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url + " => " + resp.body());
    }
    return mapper.readTree(resp.body());
  }

  static String encode(String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static String leaf(String path) {
    int i = path.lastIndexOf('/');
    return i >= 0 ? path.substring(i+1) : path;
  }

  static String last(JsonNode arr) {
    int n = arr.size();
    return n == 0 ? "" : arr.get(n-1).asText();
  }

  static String featureFromPath(String fullPath) {
    // Expecting .../com/bofa/mda/handsets/.../<feature>/test/(files...)
    // Take the segment immediately before the last "test"
    String[] parts = fullPath.split("/");
    for (int i = parts.length - 1; i >= 1; i--) {
      if ("test".equals(parts[i])) {
        return parts[i - 1];
      }
    }
    // Fallback if pattern not found
    return "unknown";
  }
}
