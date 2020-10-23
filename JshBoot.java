import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

class JshBoot {
    private List<String> classPath = new ArrayList<>();

    private static final String HTTP_CONNECT_TIMEOUT = "HTTP_CONNECT_TIMEOUT";
    private static final String HTTP_READ_TIMEOUT = "HTTP_READ_TIMEOUT";
    private static final int CONNECT_TIMEOUT;
    private static final int READ_TIMEOUT;

    private static void log(String message) {
        System.out.println(message);
    }

    private static void info(String message) {
        log("[INFO] " + message);
    }

    private static void warning(String message) {
        log("[WARN] " + message);
    }

    private static void error(String message) {
        log("[ERRN] " + message);
    }

    static {
        String connTimeout = System.getenv(HTTP_CONNECT_TIMEOUT);
        if (connTimeout != null) {
            CONNECT_TIMEOUT = Integer.parseInt(connTimeout);
        } else {
            CONNECT_TIMEOUT = 5000;
        }

        String readTimeout = System.getenv(HTTP_READ_TIMEOUT);
        if (readTimeout != null) {
            READ_TIMEOUT = Integer.parseInt(readTimeout);
        } else {
            READ_TIMEOUT = 5000;
        }
    }

    // get the file name part from the URL
    private String getFile(URL url, String repoUrl) throws MalformedURLException {
        String fn = url.getFile();
        int index;
        if ((index = fn.indexOf('?')) != -1) {
            fn = fn.substring(0, index);
        }
        final var repoUrlFile = new URL(repoUrl).getFile();
        if (fn.length() > repoUrlFile.length() && fn.startsWith(repoUrlFile)) {
            fn = fn.substring(repoUrlFile.length());
        } else {
            throw new RuntimeException("The URL '" + url + "' is not inside the repo '" + repoUrl + "'");
        }
        while (fn.charAt(0) == '/') {
            fn = fn.substring(1);
        }
        return fn;
    }

    //
    // the repository where we store the downloaded JAR files
    //
    private File repo;

    public static JshBoot localRepo(String repoDirectory) {
        JshBoot it = new JshBoot();
        it.repo = new File(repoDirectory);
        it.repo.mkdirs();
        return it;
    }

    public static JshBoot localRepo() {
        String envrepoRoot = System.getenv("JSHBOOT_JAR_REPO");
        if (envrepoRoot != null) {
            return localRepo(envrepoRoot);
        } else {
            String userHome = System.getProperty("user.home");
            return localRepo(userHome + "/.m2/repository");
        }
    }

    public JshBoot jar(String path) {
        classPath.add(path);
        return this;
    }

    public JshBoot url(String urlString) throws IOException {
        final URL url = new URL(urlString);
        final int fileNameLength = url.getFile().length();
        final String repo = url.toString().substring(0, url.toString().length() - fileNameLength);
        url(url, repo);
        return this;
    }

    private String currentRemoteRepo;
    private String currentGropId;
    private String currentVersion;

    private static class GAV {
        final String groupId;
        final String artifactId;
        final String version;

        private GAV(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }
    }

    private GAV gav(String dependency) {
        final var parts = dependency.split(":", -1);
        if (parts.length != 3) {
            throw new RuntimeException("The dependency '" + dependency + "' is malformed. It has to be 'groupId:artifactId:version'.");
        }
        return new GAV(parts[0], parts[1], parts[2]);
    }

    public JshBoot maven(String dependency) throws IOException {
        maven();
        download(gav(dependency));
        return this;
    }

    public JshBoot maven(String groupId, String artifactId, String version) throws IOException {
        maven();
        download(new GAV(groupId, artifactId, version));
        return this;
    }

    public JshBoot maven() {
        currentRemoteRepo = "https://repo.maven.apache.org/maven2";
        return this;
    }
    public JshBoot groupId(String groupId) {
        currentGropId = groupId;
        return this;
    }

    public JshBoot version(String version) {
        currentVersion = version;
        return this;
    }

    public JshBoot artifactId(String artifactId) throws IOException {
        download(new GAV(currentGropId, artifactId, currentVersion));
        return this;
    }


    public void execute(String mainClass, String ... args) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder();
        String sep = System.getProperty("path.separator");
        String cp = String.join(sep,classPath);
        List<String> arguments = new ArrayList<>();
        arguments.addAll(List.of("java", "-cp", cp, mainClass));
        arguments.addAll(Arrays.asList(args));
        info("EXECUTING '"+ String.join(" ",arguments)+"'");
        builder.command(arguments.toArray(String[]::new))
            .directory(new File("."));
        Process process = builder.start();
        process.getInputStream().transferTo(System.out);
        process.getErrorStream().transferTo(System.err);
        int exitCode = process.waitFor();
    }

    private void download(GAV gav) throws IOException {
        remoteRepo(gav, currentRemoteRepo);
    }

    private static String errDepString(GAV gav) {
        return "The dependency '" + gav.groupId + ":" + gav.artifactId + ":" + gav.version + "' is malformed.";
    }

    private void remoteRepo(GAV gav, String repo) throws IOException {
        if (gav.groupId.length() == 0) {
            throw new RuntimeException(errDepString(gav) + " groupId is empty");
        }
        if (gav.artifactId.length() == 0) {
            throw new RuntimeException(errDepString(gav) + " artifactId is empty");
        }
        if (gav.version.length() == 0) {
            throw new RuntimeException(errDepString(gav) + " version is empty");
        }
        final var url = new URL(repo + "/" + gav.groupId.replaceAll("\\.", "/") + "/" + gav.artifactId + "/" + gav.version + "/" + gav.artifactId + "-" + gav.version + ".jar");
        info("URL=" + url);
        url(url, repo);
    }

    /**
     * Download and add a file to the repo using the URL.
     *
     * @param url     pointing to the jar file
     * @param repoUrl the url to the repository. Used to calculate the hierarchy of the directory structure under the
     *                repo directory.
     * @throws IOException when the file cannot be fetched
     */
    private void url(URL url, String repoUrl) throws IOException {
        if (!repo.exists()) {
            throw new RuntimeException("The repo directory '" + repo.getAbsolutePath() + "' does not exist.");
        }
        if (!repo.isDirectory()) {
            throw new RuntimeException("The repo directory '" + repo.getAbsolutePath() + "' is not a directory.");
        }
        File jar = new File(repo.getAbsolutePath() + "/" + getFile(url, repoUrl)).getCanonicalFile();

        jar(jar.getAbsolutePath());

        // if this exists and not a snapshot
        if (jar.exists() && !jar.getAbsolutePath().contains("SNAPSHOT")) {
            info(jar.getAbsolutePath() + " is already in the repo");
            return;
        }
        info("download " + url);
        info("to file " + jar.getAbsolutePath());
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(CONNECT_TIMEOUT);
        con.setReadTimeout(READ_TIMEOUT);
        con.setInstanceFollowRedirects(true);
        final int status = con.getResponseCode();
        if (status != 200) {
            throw new IOException("GET url '" + url.toString() + "' returned " + status);
        }
        InputStream is = con.getInputStream();
        int index;
        if ((index = jar.getAbsolutePath().lastIndexOf('/')) != -1) {
            new File(jar.getAbsolutePath().substring(0, index)).mkdirs();
        }
        try (OutputStream outStream = new FileOutputStream(jar)) {
            byte[] buffer = new byte[8 * 1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                outStream.write(buffer, 0, bytesRead);
            }
        }
    }
}