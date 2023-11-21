package prlprg;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
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

record TyInst(String nm, List<Ty> tys) implements Ty {

    @Override
    public String toString() {
        return nm + (tys.isEmpty() ? ""
                : "{" + tys.stream().map(Ty::toString).collect(Collectors.joining(","))
                + "}");
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TyInst t) {
            return nm.equals(t.nm()) && tys.equals(t.tys());
        } else {
            return false;
        }
    }
}

record TyVar(String nm, Ty low, Ty up) implements Ty {

    @Override
    public String toString() {
        return (!low.equals(Ty.none()) ? low + "<:" : "") + Color.yellow(nm) + (!up.equals(Ty.any()) ? "<:" + up : "");
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TyVar t) {
            return nm.equals(t.nm()) && low.equals(t.low()) && up.equals(t.up());
        } else {
            return false;
        }
    }
}

record TyCon(String nm) implements Ty {

    @Override
    public String toString() {
        return nm;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TyCon t) {
            return nm.equals(t.nm());
        } else {
            return false;
        }
    }
}

record TyTuple(List<Ty> tys) implements Ty {

    @Override
    public String toString() {
        return "(" + tys.stream().map(Ty::toString).collect(Collectors.joining(",")) + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TyTuple t) {
            return tys.equals(t.tys());
        } else {
            return false;
        }
    }
}

record TyUnion(List<Ty> tys) implements Ty {

    @Override
    public String toString() {
        return "[" + tys.stream().map(Ty::toString).collect(Collectors.joining("|")) + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TyUnion t) {
            return tys.equals(t.tys());
        } else {
            return false;
        }
    }
}

record TyDecl(String nm, Ty ty, Ty parent, String src) implements Ty {

    @Override
    public String toString() {
        return nm + " = " + ty + " <: " + parent + Color.green("\n# " + src);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TyDecl t) {
            return nm.equals(t.nm()) && ty.equals(t.ty()) && parent.equals(t.parent());
        } else {
            return false;
        }
    }
}

record TySig(String nm, Ty ty, String src) implements Ty {

    @Override
    public String toString() {
        return "function " + nm + " " + ty + Color.green("\n# " + src);
    }
}

record TyExist(Ty v, Ty ty) implements Ty {

    static final String redE = Color.red("∃");
    static final String redD = Color.red(".");

    @Override
    public String toString() {
        return redE + v + redD + ty;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TyExist t) {
            return v.equals(t.v()) && ty.equals(t.ty());
        } else {
            return false;
        }
    }
}

class GenDB {

    final HashMap<String, TyDecl> tydb = new HashMap<>();
    final HashMap<String, List<TySig>> sigdb = new HashMap<>();

    public GenDB() {
        addTyDecl(new TyDecl("Function", new TyInst("Function", List.of()), Ty.none(), ""));
        addTyDecl(new TyDecl("Tuple", new TyInst("Tuple", List.of()), Ty.none(), ""));
        addTyDecl(new TyDecl("Union", new TyInst("Union", List.of()), Ty.none(), ""));
    }

    final void addTyDecl(TyDecl ty) {
        if (tydb.containsKey(ty.nm())) {
            System.out.println("Warning: " + ty.nm() + " already exists, replacing");
        }
        tydb.put(ty.nm(), ty);
    }

    final void addSig(TySig sig) {
        if (!sigdb.containsKey(sig.nm())) {
            sigdb.put(sig.nm(), new ArrayList<>());
        }
        var list = sigdb.get(sig.nm());
        list.add(sig);
    }

    private Ty fixVars(Ty ty) {
        switch (ty) {
            case TyVar t -> {
                return new TyVar(t.nm(), Ty.none(), Ty.any());
            }
            case TyInst t -> {
                if (t.tys().isEmpty()) {
                    return tydb.containsKey(t.toString()) ? t
                            : new TyVar(t.nm(), Ty.none(), Ty.any());
                } else {
                    return new TyInst(t.nm(), t.tys().stream().map(this::fixVars).collect(Collectors.toList()));
                }
            }
            case TyCon t -> {
                return t;
            }
            case TyTuple t -> {
                return new TyTuple(t.tys().stream().map(this::fixVars).collect(Collectors.toList()));
            }
            case TyUnion t -> {
                return new TyUnion(t.tys().stream().map(this::fixVars).collect(Collectors.toList()));
            }
            case TyExist t -> {
                var maybeVar = t.v();
                var body = fixVars(t.ty());
                if (maybeVar instanceof TyVar defVar) {
                    var tv = new TyVar(defVar.nm(), fixVars(defVar.low()), fixVars(defVar.up()));
                    return new TyExist(tv, body);
                } else if (maybeVar instanceof TyInst inst) {
                    if (inst.tys().isEmpty()) {
                        if (tydb.containsKey(inst.toString())) {
                            return body;
                        } else {
                            var tv = new TyVar(inst.nm(), Ty.none(), Ty.any());
                            return new TyExist(tv, body);
                        }
                    } else {
                        return new TyInst(inst.nm(), inst.tys().stream().map(this::fixVars).collect(Collectors.toList()));
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
        return new TyDecl(d.nm(), fixVars(d.ty()), fixVars(d.parent()), d.src());
    }

    private TySig phase1sig(TySig s) {
        return new TySig(s.nm(), fixVars(s.ty()), s.src());
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

        for (var name : sigdb.keySet()) {
            var sigs = sigdb.get(name);
            for (var sig : sigs) {
                var newsig = phase1sig(sig);
                System.out.println(newsig.toString());
            }
        }
    }
}

class Color {

    //static String lightGray = "\u001B[37m";
    static String red = "\u001B[31m";
    static String resetText = "\u001B[0m"; // ANSI escape code to reset text color
    static String green = "\u001B[32m";
    // static String yellow = "\u001B[34m";
    static String yellow = "\u001B[33m";
    //static String cyan = "\u001B[36m";
    //static String magenta = "\u001B[35m";

    static String red(String s) {
        return red + s + resetText;
    }

    static String yellow(String s) {
        return yellow + s + resetText;
    }

    static String green(String s) {
        return green + s + resetText;
    }
}
