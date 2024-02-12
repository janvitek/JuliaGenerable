package prlprg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class JuliaUtils {
    private static Path bin;
    private static Path depot;
    private static Path typeDiscover;
    private static List<String> packages;
    private static boolean includeLoaded;

    /**
     * Test that we have a working julia. Make sure we also have a shared
     * environment that contains the TypeDiscover.jl package.
     */
    public static void setup() {
        bin = Paths.get(App.Options.juliaBin);
        depot = Paths.get(App.Options.juliaDepot);
        typeDiscover = Paths.get(App.Options.root).resolve(App.Options.typeDiscover);

        if (!Files.isDirectory(depot)) {
            if (!depot.toFile().mkdirs()) throw new RuntimeException("Couldn't create depot directory " + depot);
        }
        if (!Files.isDirectory(typeDiscover)) {
            throw new RuntimeException("Couldn't find the TypeDiscover.jl package at " + typeDiscover);
        }
        
        var pf = Paths.get(App.Options.root).resolve(App.Options.inputs).resolve(App.Options.juliaPkgsFile);
        if (Files.notExists(pf)) {
            throw new RuntimeException("Couldn't find the packages file at " + pf);
        }
        try {
            var ps = Files.readAllLines(pf);
            includeLoaded = ps.contains("@LOADED");
            packages = ps.stream().filter((p) -> !p.equals("@LOADED")).toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        testJulia();
        setupSharedEnv();
    }

    /**
     * Builder for a julia command. Start by calling `julia()`, add args, finalize
     * by calling `go()`. Returns a ProcessBuilder which when started, runs the
     * configured julia binary with the right depot.
     */
    private static class JuliaScriptBuilder {
        private List<String> args;
        private Path wd;
        private HashMap<String, String> env;

        JuliaScriptBuilder() {
            args = new ArrayList<>();
            wd = null;
            env = new HashMap<>();

            arg(bin.toString()).env("JULIA_DEPOT_PATH", depot.toString());
        }

        JuliaScriptBuilder arg(String arg) {
            this.args.add(arg);
            return this;
        }

        JuliaScriptBuilder args(String... args) {
            for (var a : args)
                arg(a);
            return this;
        }

        JuliaScriptBuilder directory(Path p) {
            wd = p;
            return this;
        }

        JuliaScriptBuilder env(String key, String value) {
            env.put(key, value);
            return this;
        }

        ProcessBuilder go() {
            var pb = new ProcessBuilder(args);
            if (wd != null)
                pb.directory(wd.toFile());
            pb.environment().putAll(env);
            return pb;
        }
    }

    public static JuliaScriptBuilder julia() {
        return new JuliaScriptBuilder();
    }

    private static void testJulia() {
        var pb = julia().args("-e", "println(\"Using julia v$VERSION with depot `$(only(DEPOT_PATH))'\")").go();
        try {
            var p = pb.start();
            String output = loadStream(p.getInputStream());
            String error = loadStream(p.getErrorStream());
            var ret = p.waitFor();
            if (ret != 0) throw new RuntimeException("Non-zero return code (" + ret + ") from " + pb.command());
            if (!error.isEmpty()) throw new RuntimeException("Non-empty stderr (`" + error + "') from " + pb.command());
            App.print(output);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setupSharedEnv() {
        var pkgList = packages.stream().map((s) -> "\"" + s + "\"").collect(Collectors.joining(", "));
        var pb = julia().go();
        try {
            var p = pb.start();
            writeStream(
                p.getOutputStream(),
                """
                import Pkg
                Pkg.activate("jgextra"; shared=true)
                Pkg.add([%s])
                Pkg.add("MethodAnalysis")
                Pkg.develop(path="%s")
                Pkg.instantiate()
                Pkg.status()
                """.formatted(pkgList, typeDiscover));
            String output = loadStream(p.getInputStream());
            String error = loadStream(p.getErrorStream());
            var ret = p.waitFor();
            if (ret != 0) throw new RuntimeException("Non-zero return code (" + ret + ") from " + pb.command() + "; stdout=`" + output + "'; stderr=`" + error + "'");
            App.print(output);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void runTypeDiscovery() {
        var pkgList = packages.stream().collect(Collectors.joining(", "));
        var pb = julia().go();
        try {
            var p = pb.start();
            writeStream(
                p.getOutputStream(),
                """
                push!(LOAD_PATH, "@jgextra")
                using TypeDiscover
                using %s
                typediscover(append!([%s], %s ? Base.loaded_modules_array() : []); funcfile="%s", typefile="%s", aliasfile="%s")
                """.formatted(pkgList, pkgList, includeLoaded ? "true" : "false", App.Options.functionsPath, App.Options.typesPath, App.Options.aliasesPath));
            String output = loadStream(p.getInputStream());
            String error = loadStream(p.getErrorStream());
            var ret = p.waitFor();
            if (ret != 0) throw new RuntimeException("Non-zero return code (" + ret + ") from " + pb.command() + "; stdout=`" + output + "'; stderr=`" + error + "'");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void runTests(Path tests, Path tmp) {
        var pb = julia().directory(tmp).args(tests.toString()).go();
        try {
            pb.start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Collect stdout/stderr of a process to a String.
     */
    private static String loadStream(InputStream s) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(s));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null)
            sb.append(line).append("\n");
        return sb.toString();
    }

    /**
     * Send a string to stdin of a process.
     */
    private static void writeStream(OutputStream s, String what) throws IOException {
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s));
        bw.write(what);
        bw.close();
    }
}
