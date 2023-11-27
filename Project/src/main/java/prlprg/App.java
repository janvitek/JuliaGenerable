package prlprg;

public class App {

    static boolean debug = false;
    static GenDB db = new GenDB();
    static String dir = "../Inputs/";
    static String tstr = """
      abstract type Any end (basbdsa)
 (closure) struct RAICode.AIR.var{type_annos} <: Function end (from module RAICode.AIR)
      abstract type Random.Sampler{Y} end (nasndsa)
      struct Random.SamplerSimple{T, S, E} <: Random.Sampler{E} end (asdsa)
      """;
    static String str = """
              function _tuplen(t::Type{<:Tuple}) in RAICode.QueryEva...utils.jl:103 (method for generic function _tuplen)
              function f() @  asda/asds
              function ch(A::Stride{v } where v<:Union{  ComplexF64}, ::Type{LUp })
              """;

    public static void main(String[] args) {
        var p = new Parser();
        p = debug ? p.withString(tstr) : p.withFile(dir + "raicode_types.jlg");
        while (!p.peek().isEOF()) {
            db.addTyDecl(TypeDeclaration.parse(p).toTy());
        }
        debug = false;
        p = new Parser();
        p = debug ? p.withString(str) : p.withFile(dir + "raicode_functions.jlg");
        while (!p.peek().isEOF()) {
            db.addSig(Function.parse(p).toTy());
        }
        db.cleanUp();
        new Generator(db).gen();
    }
}
