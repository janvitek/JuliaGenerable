package prlprg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import prlprg.App.Options;

public class JuliaUtils {

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

            arg(Paths.get(App.Options.juliaBin).toString());

            env("JULIA_LOAD_PATH", ":@" + App.Options.juliaEnv);

            if (App.Options.juliaDepot != null)
                env("JULIA_DEPOT_PATH", App.Options.depotPath().toString());

            if (App.Options.juliaProject != null)
                arg("--project=" + App.Options.projectPath());
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

    private static String unsetAnsiColors() {
        return """    
            foreach(k -> Base.text_colors[k] = "", keys(Base.text_colors))
            foreach(k -> Base.disable_text_style[k] = "", keys(Base.disable_text_style))
            """;
    }

    public static void checkJulia() {
        var script = """
            println("┌ Hello from Julia!")
            println("│   Version: $VERSION")
            println("│   Project: $(Base.active_project())")
            println("│   Depot: $DEPOT_PATH")
            println("└   Load path: $LOAD_PATH")
            """;
        var pb = julia().go();
        try {
            var p = pb.start();
            writeStream(p.getOutputStream(), script);
            String output = loadStream(p.getInputStream());
            String error = loadStream(p.getErrorStream());
            var ret = p.waitFor();
            if (ret != 0) throw new RuntimeException("Non-zero return code (" + ret + ") from " + pb.command());
            if (!error.isEmpty()) throw new RuntimeException("Non-empty stderr (`" + error + "') from " + pb.command());
            App.print(output);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        setupEnv();
    }

    private static void setupEnv() {
        var script = unsetAnsiColors();

        if (Options.runTypeDiscovery) {
            script += """
                import Pkg
                Pkg.activate("%s"; shared=true)
                Pkg.develop(path="%s")
                Pkg.instantiate()
                """.formatted(App.Options.juliaEnv, App.Options.typeDiscoverPath());
        }

        if (App.Options.juliaProject != null) {
            script += """
                import Pkg
                Pkg.activate("%s")
                Pkg.instantiate()
                Pkg.build()
                """.formatted(App.Options.projectPath());
            script += """
                registry = Set(); nothing
                repo = Set(); nothing
                path = Set(); nothing
                transitivedeps(deps, all, seen) = begin
                  for (name, uuid) in deps
                    name in seen && continue
                    push!(seen, name)
                    pkginfo = all[uuid]
                    if !pkginfo.is_direct_dep
                      pkginfo.is_tracking_path ? push!(path, pkginfo) :
                      pkginfo.is_tracking_registry ? push!(registry, pkginfo) :
                      pkginfo.is_tracking_repo ? push!(repo, pkginfo) :
                      error("package $name isn't tracking anything")
                    end
                    transitivedeps(pkginfo.dependencies, all, seen)
                  end
                end; nothing
                transitivedeps(Pkg.project().dependencies, Pkg.dependencies(), Set()); nothing
                Pkg.activate("%s"; shared=true)
                isempty(registry) || Pkg.add(String[p.name for p in registry]); nothing
                isempty(repo) || Pkg.add(Pkg.PackageSpec[Pkg.PackageSpec(url=p.git_source, rev=p.git_revision) for p in repo]); nothing
                isempty(path) || Pkg.develop(Pkg.PackageSpec[Pkg.PackageSpec(path=p.source) for p in path]); nothing
                Pkg.instantiate()
                """.formatted(App.Options.juliaEnv);
        }

        if (!script.isEmpty()) {
            var pb = julia().arg("--color=no").go();
            try {
                var p = pb.start();
                writeStream(p.getOutputStream(), script);
                String output = loadStream(p.getInputStream());
                String error = loadStream(p.getErrorStream());
                var ret = p.waitFor();
                if (ret != 0) throw new RuntimeException("Non-zero return code (" + ret + ") from " + pb.command() + "; stdout=`" + output + "'; stderr=`" + error + "'");
                if (App.Options.verbose) {
                    App.print("[JuliaUtils.setupEnv] stdout:\n" + output + "\n");
                    App.print("[JuliaUtils.setupEnv] stderr:\n" + error + "\n");
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void runTypeDiscovery() {
        String packages = null;
        {
            var script = unsetAnsiColors() + """
                imports = String[]; nothing
                import Pkg
                isnothing(Pkg.project().name) || push!(imports, Pkg.project().name); nothing
                foreach(k -> push!(imports, k), keys(Pkg.project().dependencies))
                println(join(imports, ", "))
                """;
            var pb = julia().arg("--color=no").go();
            try {
                var p = pb.start();
                writeStream(p.getOutputStream(), script);
                String output = loadStream(p.getInputStream());
                String error = loadStream(p.getErrorStream());
                p.waitFor();
                if (App.Options.verbose) {
                    App.print("[JuliaUtils.runTypeDiscovery 1/2] stdout:\n " + output + "\n");
                    App.print("[JuliaUtils.runTypeDiscovery 1/2] stderr:\n" + error + "\n");
                }
                packages = output.strip();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (packages == null) throw new RuntimeException("Couldn't get the list of packages for project");

        var script = unsetAnsiColors() + """
            import TypeDiscover
            %s
            TypeDiscover.typediscover(; funcfile="%s", typefile="%s", aliasfile="%s")
            """.formatted(
                packages.isEmpty() ? "" : "import " + packages,
                App.Options.functionsPath(),
                App.Options.typesPath(),
                App.Options.aliasesPath());

        var pb = julia().arg("--color=no").go();
        try {
            var p = pb.start();
            writeStream(p.getOutputStream(), script);
            String output = loadStream(p.getInputStream());
            String error = loadStream(p.getErrorStream());
            var ret = p.waitFor();
            if (ret != 0) throw new RuntimeException("Non-zero return code (" + ret + ") from " + pb.command() + "; stdout=`" + output + "'; stderr=`" + error + "'");
            if (App.Options.verbose) {
                App.print("[JuliaUtils.runTypeDiscovery 2/2] stdout:\n" + output + "\n");
                App.print("[JuliaUtils.runTypeDiscovery 2/2] stderr:\n" + error + "\n");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void runTests(Path tests, Path tmp) {
        var pb = julia().directory(tmp).arg(tests.toString()).go();
        try {
            var p = pb.start();
            String output = loadStream(p.getInputStream());
            String error = loadStream(p.getErrorStream());
            var ret = p.waitFor();
            if (App.Options.verbose) {
                App.print("[JuliaUtils.runTests] return code: " + ret);
                App.print("[JuliaUtils.runTests] stdout:\n" + output + "\n");
                App.print("[JuliaUtils.runTests] stderr:\n" + error + "\n");
            }
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
        if (App.Options.verbose) {
            App.print("[JuliaUtils.writeStream] writing to stdin:\n```\n" + what + "```\n");
        }
        bw.write(what);
        bw.close();
    }

    public static void runConcretenessSanityChecks(Path imports, Path out, HashSet<String> abstracts, HashSet<String> concretes) {
        try {
            try (var w = new BufferedWriter(new FileWriter(out.toString()))) {
                w.write(unsetAnsiColors() + """
                    include("%s")
                    io = IOContext(stdout, :module => nothing, :compact => false)
                    check(io, kind::Symbol, @nospecialize(t::Type)) = begin
                      try
                        @assert kind in [:abstract, :concrete]
                        if (kind === :abstract && isconcretetype(t)) || (kind === :concrete && !isconcretetype(t))
                          println(io, "  Should not be $kind: ", t)
                        end
                      catch e
                        println(io, e)
                      end
                      nothing
                    end

                    """.formatted(imports));
                for (var a : abstracts) {
                    w.write("check(io, :abstract, %s)\n".formatted(a));
                }
                for (var c : concretes) {
                    w.write("check(io, :concrete, %s)\n".formatted(c));
                }
                w.write("println(\"Checked %s types\")\n".formatted(abstracts.size() + concretes.size()));
            }

            var pb = julia().args("--color=no", out.toString()).go();
            var p = pb.start();
            String output = loadStream(p.getInputStream());
            String error = loadStream(p.getErrorStream());
            var ret = p.waitFor();
            if (App.Options.verbose) {
                App.print("[JuliaUtils.runConcretenessSanityChecks] return code: " + ret);
                App.print("[JuliaUtils.runConcretenessSanityChecks] stdout:\n" + output + "\n");
                App.print("[JuliaUtils.runConcretenessSanityChecks] stderr:\n" + error + "\n");
            }
            App.print("Sanity checks from " + out + ":");
            App.print(output);

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
