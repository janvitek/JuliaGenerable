package prlprg;

public class App {

    public static boolean debug, PRINT_HIERARCHY, NO_CLOSURES, SHORTEN;
    static GenDB db = new GenDB();
    static String dir, types, functions;
    static String[] defaultArgs = {
        "-d=TRUE", // run micro tests
        "-c=LIGHT", // color the output
        "-r=../Inputs/", // root directory with input files
        "-f=f-test.jlg", // file with function signatures
        "-t=t.jlg", // file with type declarations
        "-h=TRUE", // print hierarchy
        "-i=FALSE", // ignore closures
        "-s=TRUE", // print shorter type names
    };

    public static void main(String[] args) {
        parseArgs(defaultArgs); // set default values
        parseArgs(args); // override them with command line arguments
        var p = new Parser();
        p = debug ? p.withString(tstr) : p.withFile(dir + types);
        while (!p.isEmpty()) {
            db.addTyDecl(TypeDeclaration.parse(p.sliceLine()).toTy());
        }
        p = new Parser();
        p = debug ? p.withString(str) : p.withFile(dir + functions);
        while (!p.isEmpty()) {
            db.addSig(Function.parse(p.sliceLine()).toTy());
        }
        db.cleanUp();
        new Generator(db).gen();
    }

    /// TODO: this is not supported struct R{v, a, *, vv, Tuple} <: Function end
    /// Currenetly, we only support types that have variables in the arguments. It is unclear why one would
    /// want to have '*' and 'Tuple'
    //
    // TODO: this is not supported
    //    struct C{T<:Tuple{}, A<:B{Tuple{}}} <: B{T<:Tuple{}} end (from)
    // RHS has a bound we support
    //   struct C{T<:Tuple{}, A<:B{Tuple{}}} <: B{T} end (from)
    static String tstr = """
      abstract type Tuple end
      abstract type B{X} end
      struct C{T<:Tuple{}, A<:B{Tuple{}}} <: B{T} end
      struct A{T<:B{<:B}} <: B{T} end
      abstract type Val{X} end
      abstract type R <: B{Val{:el}} end
      struct D{T, S, E} <: B{E} end
      struct Int32 end
      struct T1 <: Val{:el} end
      struct T2 <: Val{10} end
      -struct T3 <: Val{\"hi\"} end
    """;
    static String str = """
     function two(a::A{Int32}, b::Int32)
     function a(x::D{K, V}, y::Union{B1{K, V}, B2{K, V}} where {K, V}, w::Union{B1{K, V}, B2{K, V}}) where {K, V}
     function _tl(t::Type{<:Tuple})
     function f() @  asda/asds
     function ch(A::B{v} where v<:Union{Int32}, ::Type{A})
    """;

    static void parseArgs(String[] args) {
        for (var arg : args) {
            if (arg.startsWith("-d")) { // debug
                debug = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-h")) { // debug
                PRINT_HIERARCHY = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-s")) { // debug
                SHORTEN = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-i")) { // debug
                NO_CLOSURES = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-d")) { // debug
                debug = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-r")) {  // root directory
                dir = arg.substring(3).strip();
            } else if (arg.startsWith("-t")) {
                types = arg.substring(3).strip();
            } else if (arg.startsWith("-f")) {
                functions = arg.substring(3).strip();
            } else if (arg.startsWith("-c")) { // Color mode
                CodeColors.mode = switch (arg.substring(3).strip()) {
                    case "DARK" ->
                        CodeColors.Mode.DARK;
                    case "LIGHT" ->
                        CodeColors.Mode.LIGHT;
                    default ->
                        CodeColors.Mode.NONE;
                };
            } else {
                System.err.println("Unknown argument: " + arg);
                System.exit(1);
            }
        }
    }
}
