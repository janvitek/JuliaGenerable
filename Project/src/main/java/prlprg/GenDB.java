package prlprg;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

interface Ty {

    final static Ty Any = new TyInst("Any", List.of());
    final static Ty None = new TyUnion(List.of());

    static Ty any() {
        return Any;
    }

    static Ty none() {
        return None;
    }
}

record TyInst(String nm, List<Ty> params) implements Ty {

    @Override
    public String toString() {
        return nm + (params.isEmpty() ? ""
                : "{" + params.stream().map(Ty::toString).collect(Collectors.joining(","))
                + "}");
    }
}

record TyVar(String nm, Ty low, Ty up) implements Ty {

    @Override
    public String toString() {
        return (low != Ty.none() ? low + " <: " : "") + Color.blue(nm) + (up != Ty.any() ? " <: " + up : "");
    }
}

record TyCon(String nm) implements Ty {

}

record TyTuple(List<Ty> tys) implements Ty {

    @Override
    public String toString() {
        return "(" + tys.stream().map(Ty::toString).collect(Collectors.joining(",")) + ")";
    }
}

record TyUnion(List<Ty> tys) implements Ty {

    @Override
    public String toString() {
        return "[" + tys.stream().map(Ty::toString).collect(Collectors.joining("|")) + "]";
    }
}

record TyDecl(String nm, Ty ty, Ty parent, String src) implements Ty {

    @Override
    public String toString() {
        return nm + " = " + ty + " <: " + parent + Color.gray("\n# " + src);
    }
}

record TySig(String nm, Ty ty) implements Ty {

}

record TyExist(Ty v, Ty ty) implements Ty {

    static final String redE = Color.red("∃");

    @Override
    public String toString() {
        return redE + v + "." + ty;
    }
}

class GenDB {

    HashMap<String, TyDecl> tydb = new HashMap<>();
    HashMap<String, List<Ty>> sigdb = new HashMap<>();

    void addTyDecl(TyDecl ty) {
        if (tydb.containsKey(ty.nm())) {
            System.out.println("Warning: " + ty.nm() + " already exists, replacing");
        }
        tydb.put(ty.nm(), ty);
    }

    void addSig(TySig sig) {
        sigdb.put(sig.nm(), List.of(sig.ty()));
    }

    private Ty phase1_unwrap(Ty ty) {
        switch (ty) {
            case TyVar t -> {
                return new TyVar(t.nm(), phase1_unwrap(t.low()), phase1_unwrap(t.up()));
            }
            case TyInst t -> {
                return new TyInst(t.nm(), t.params().stream().map(this::phase1_unwrap).collect(Collectors.toList()));
            }
            case TyCon t -> {
                return t;
            }
            case TyTuple t -> {
                return new TyTuple(t.tys().stream().map(this::phase1_unwrap).collect(Collectors.toList()));
            }
            case TyUnion t -> {
                return new TyUnion(t.tys().stream().map(this::phase1_unwrap).collect(Collectors.toList()));
            }
            case TyExist t -> {
                var maybeVar = t.v();
                var body = phase1_unwrap(t.ty());
                if (maybeVar instanceof TyVar defVar) {
                    return new TyExist(defVar, body);
                } else if (maybeVar instanceof TyInst inst) {
                    if (inst.params().isEmpty()) {
                        if (tydb.containsKey(inst.toString())) {
                            return body;
                        } else {
                            var tv = new TyVar(inst.toString(), Ty.none(), Ty.any());
                            return new TyExist(tv, body);
                        }
                    } else {
                        return new TyInst(inst.nm(), inst.params().stream().map(this::phase1_unwrap).collect(Collectors.toList()));
                    }
                } else {
                    return body;
                }
            }
            default ->
                throw new Error("Unknown type: " + ty);
        }
    }

    private TyDecl phase1(TyDecl d) {
        return new TyDecl(d.nm(), phase1_unwrap(d.ty()), d.parent(), d.src());
    }

    public void cleanUp() {
        // At this point the definitions in the DB are ill-formed, as we don't
        // know what identifiers refer to types and what identifiers refer to
        // variables. We asume that we have seen all types declarations, so
        // anything that is not in tydb is a variable. Now, one can define
        // a variable that shadows a type, so there may be some ambiguity
        // still.
        // Phase 1 will try to clean up types by removing obvious variables.

        var newTydb = new HashMap<String, Ty>();
        for (var name : tydb.keySet()) {
            var newdecl = phase1(tydb.get(name));
            System.out.println(newdecl.toString());
            newTydb.put(name, newdecl.ty());
        }

    }
}

class Color {

    //static String lightGray = "\u001B[37m";
    static String red = "\u001B[31m";
    static String resetText = "\u001B[0m"; // ANSI escape code to reset text color
    static String green = "\u001B[32m";
    static String blue = "\u001B[34m";
    static String yellow = "\u001B[33m";
    //static String cyan = "\u001B[36m";
    //static String magenta = "\u001B[35m";

    static String red(String s) {
        return red + s + resetText;
    }

    static String blue(String s) {
        return blue + s + resetText;
    }

    static String gray(String s) {
        return green + s + resetText;
    }
}
