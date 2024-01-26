package prlprg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import prlprg.App.Timer;
import prlprg.Parser.MethodInformation;

/**
 * Class for orchestrating the generation of type stability tests.
 *
 * @author jan
 */
class Orchestrator {

    private static int num = 0; // internal counter for unique names

    /**
     * Generate a unique number in a possibly concurrent context. These numbers are
     * used for temproary director names.
     */
    static synchronized int nextNum() {
        return num++;
    }

    GenDB it; // reference to the GenDB for convenience

    /**
     * Create an orchestrator for the given GenDB.
     */
    Orchestrator() {
        this.it = GenDB.it;
    }

    /**
     * Generate the tests and run them.
     */
    void orchestrate() {
        var ctxt = new Context();

        Timer t = new Timer().start();
        createPkgs(ctxt);
        //        createGroundTests(ctxt, testsForGroundSigs());
        createGroundTests(ctxt, testsForAllFuns());
        App.print("Created " + ctxt.count + " tests in " + t.stop());

        t = new Timer().start();
        exec(ctxt);
        App.printSeparator();
        App.print("Executed " + ctxt.count + " tests in " + t.stop());

        t = new Timer().start();
        readResults(ctxt);
        App.print("Read " + ctxt.count + " results in " + t.stop());

        System.exit(0);
    }

    /**
     * A context remembers where this generator is writing to. We assume there will
     * be multiple instances working concurrently (at some point).
     *
     * If we ever get to multiple concurent tasks running in parallel, then each
     * should have its own context, with the same root but different tmp
     * directories. One will have to figure out what is private to each instance and
     * what can be shared across them.
     */
    class Context {

        Path root; // for all data procduced by this JVM
        Path tmp; // path to the temp directory that holds the Julia results e.g. /tmp/t0
        Path imports; // complete path for the file that has all the inmport statements
        Path tests; // complete path for the file that holds the code to run the tests
        List<String> testFiles = new ArrayList<>(); // the name of the test files created
        int count = 0; // how many fles we actually see in the /tmp/t0 dir after Julia has run

        /**
         * Create a context with a temporary directory.
         */
        public Context() {
            var dt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            try {
                var p = Paths.get("/tmp").resolve("jl_" + dt);
                if (Files.exists(p)) {
                    delete(p);
                }
                root = Files.createDirectory(p);
                p = p.resolve("out." + nextNum());
                tmp = Files.createDirectories(p);
            } catch (IOException e) {
                throw new Error(e); // deletion shouold not fail... without a valid context there is not much we can do
            }
            imports = root.resolve("imports.jl");
            tests = root.resolve("tests.jl");
        }

        /**
         * Recursively delete the contents of the directory
         *
         * @param path
         *                 to the directory to delete
         */
        final void delete(Path p) {
            try {
                // Recursively delete the contents of the directory
                Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes _a) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException _e) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new Error(e); // not sure what else but rethrow if we fail to delete, we could ignore.e..
            }
        }
    }

    /**
     * Create the packages file that list all the packages that were referenced in
     * the DB.
     */
    void createPkgs(Context ctxt) {
        var pkgs = new HashSet<String>();
        for (var p : it.names.packages) {
            if (p.startsWith("Base.") || p.startsWith("Core.") || p.startsWith("Pkg.") || p.equals("Base") || p.equals("Core") || p.equals("Pkg")) {
                continue;
            }
            var prefix = p.contains(".") ? p.substring(0, p.indexOf(".")) : p;
            pkgs.add(prefix);
        }
        try {
            try (var importsf = new BufferedWriter(new FileWriter(ctxt.imports.toString()))) {
                importsf.write("using Pkg\n");
                for (var p : pkgs) {
                    importsf.write("Pkg.add(\"" + p + "\")\nimport " + p + "\n");
                }
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    private static final String HEADER = """
            include("imports.jl")
            using InteractiveUtils
            InteractiveUtils.highlighting[:warntype] = false

            macro WARNTYPE(e1, e2, f)
              quote
                buffer = IOBuffer()
                try
                    code_warntype(IOContext(buffer, :color => true), $(esc(e1)), $(esc(e2)))
                catch e
                    try
                      println(buffer, "Exception occurred: ", e)
                    catch e
                    end
               	end
               	open($(esc(f)), "w") do file
                    write(file, String(take!(buffer)))
               	end
              end
            end

            """;

    /**
     * Create stability tests for a list of requests. This generates a Julia program
     * that starts by importing all packages that are referenced in the DB, and then
     * defines the testing macro. The list of Test request is used to generate one
     * stability test per line.
     * 
     * The context remmebers the name of the files that will hold the results of
     * each stability test and the overall count of files. This is to check that all
     * the requested tests succeeded. (They could fail if there is a bug in our code
     * and that results in a Julia error.)
     */
    void createGroundTests(Context ctxt, List<Test> tests) {
        try {
            try (var w = new BufferedWriter(new FileWriter(ctxt.tests.toString()))) {
                w.write(HEADER);
                int cnt = 0;
                for (var t : tests) {
                    ctxt.testFiles.add(t.file);
                    cnt++;
                    w.write(t.toTest());
                }
                ctxt.count = cnt;
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    /**
     * Test hold information to generate one stability test: the name of the
     * function, the tuple of argument types, and the file in which to store the
     * results of code_warntype.
     */
    record Test(String name, String args, String file) {
        /**
         * Generate the Julia code for the stability test using the @WARNTYPE macro.
         * This consists of the name of the method, the arguments, and the string
         * containing the file name in which this will be outpput.
         */
        String toTest() {
            return """
                        @WARNTYPE  %s  [%s] "%s"
                    """.formatted(name, args, file);
        }
    }

    /**
     * Generate the requests for testing all the functions that have all concrete
     * argument types.
     */
    List<Test> testsForGroundSigs() {
        int cnt = 0;
        var tests = new ArrayList<Test>();
        for (var s : it.sigs.allSigs()) {
            if (s.isGround()) {
                var nm = "t" + cnt++ + ".tst";
                var t = ((Tuple) s.ty()).tys().stream().map(Type::toJulia).collect(Collectors.joining(", "));
                tests.add(new Test(juliaName(s), t, nm));
            }
        }
        return tests;
    }

    /**
     * Generate the requests for testing all the functions with arguments set to
     * Any. This will result in code_warntype returning all possible methods for the
     * requested function names.
     * 
     * This will be a good starting point, as we believe that functions that are
     * stable under Any are stable under more specific types.
     * 
     * Furthermore, we can use the internal type information (local variables and
     * called methods) as hints for future tests.
     */
    List<Test> testsForAllFuns() {
        int cnt = 0;
        var tests = new ArrayList<Test>();
        var seen = new HashSet<String>();
        for (var s : it.sigs.allSigs()) {
            var signameArity = s.nm().toString() + s.arity();
            if (seen.contains(signameArity)) {
                continue;
            }
            seen.add(signameArity);
            var nm = "t" + cnt++ + ".tst";
            var anys = new ArrayList<Type>();
            for (var i = 0; i < s.arity(); i++)
                anys.add(it.any);
            var t = anys.stream().map(Type::toJulia).collect(Collectors.joining(", "));
            tests.add(new Test(juliaName(s), t, nm));
        }
        return tests;
    }

    /**
     * Read the results of the tests as genereated by Julia.
     */
    void readResults(Context ctxt) {
        File dir = ctxt.tmp.toFile();
        FilenameFilter filter = (file, name) -> name.endsWith(".tst");
        File[] files = dir.listFiles(filter);
        if (files == null) {
            App.print("Directory does not exist.");
            return;
        }
        if (files.length != ctxt.count) {
            App.print("Expected " + ctxt.count + " files, found " + files.length);
        }
        int count = 0;
        for (File file : files) {
            try {
                var p = new Parser().withFile(file.toString());
                var ms = MethodInformation.parse(p, file.toString());
                if (!ms.isEmpty()) {
                    count++;
                }
                for (var m : ms) {
                    var nm = m.sig.nm();

                    var siginfo = it.sigs.get(nm.operationName());

                    if (siginfo == null) {
                        App.print(nm + " not found !");
                    }
                    var nms = it.sigs.allNames();

                    App.output(m);
                }
            } catch (Throwable e) {
                App.output("Error parsing " + file.toString() + ": " + e.getMessage());
            }
        }
        if (count != ctxt.count) {
            App.print("Expected " + ctxt.count + " methods, found " + count);
        }
    }

    /**
     * Convert a Sig's name to a Julia name.
     */
    String juliaName(Sig s) {
        return s.nm().toJulia();
    }

    /**
     * Execute a Julia script.
     *
     * @param dir
     *                the directory to execute in
     */
    void exec(Context ctxt) {
        try {
            var pb = new ProcessBuilder("julia", ctxt.tests.toString());
            pb.directory(ctxt.tmp.toFile());
            var process = pb.start();
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}