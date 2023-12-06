package prlprg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class Generator {

    interface Type {

        @Override
        public String toString();
    }

    static Inst any = new Inst("Any", List.of());
    static Union none = new Union(List.of());

    static boolean isAny(Type t) {
        return t instanceof Inst i && i.nm.equals("Any");
    }

    static boolean isNone(Type t) {
        return t instanceof Union u && u.tys.isEmpty();
    }

    // A construtor instance may have type parameters, examples are: Int32 and Vector{Int,N}. The LHS
    // of a type declaration can only have bound variables. The RHS of a type declaration can have a
    // mix of instance and variables (bound on LHS of <:). On its own, an instance should not have
    // free variables.
    record Inst(String nm, List<Type> tys) implements Type {

        @Override
        public String toString() {
            var args = tys.stream().map(Type::toString).collect(Collectors.joining(","));
            var snm = shortener.shorten(nm);
            return snm + (tys.isEmpty() ? "" : "{" + args + "}");
        }

    }

    // A variable refers to a Bound in an enclosing Exist. Free variables are not expected.
    public record Var(Bound b) implements Type {

        @Override
        public String toString() {
            return CodeColors.variable(b.nm());
        }

    }

    // A Bound introduces a variable, with an upper and a lower bound. Julia allows writing inconsistent
    // bounds, i.e. !(low <: up). These are meaningless types which cannot be used. We do not check this.
    // We check that types are well-formed (no undefined constructor and no free variables)
    record Bound(String nm, Type low, Type up) {

        @Override
        public String toString() {
            return (!isNone(low) ? low + "<:" : "") + CodeColors.variable(nm) + (!isAny(up) ? "<:" + up : "");
        }
    }

    // A constant, such as a number, character or string. The implementation of the parser does not attempt
    // do much we constant, they are treated as uninterpreted strings.
    public record Con(String nm) implements Type {

        @Override
        public String toString() {
            return CodeColors.comment(nm);
        }

    }

    record Exist(Bound b, Type ty) implements Type {

        @Override
        public String toString() {
            return CodeColors.exists("∃") + b + CodeColors.exists(".") + ty;
        }

    }

    record Union(List<Type> tys) implements Type {

        @Override
        public String toString() {
            var str = tys.stream().map(Type::toString).collect(Collectors.joining(CodeColors.union("|")));
            return CodeColors.union("[") + str + CodeColors.union("]");
        }

    }

    public record Tuple(List<Type> tys) implements Type {

        @Override
        public String toString() {
            var str = tys.stream().map(Type::toString).collect(Collectors.joining(CodeColors.tuple(",")));
            return CodeColors.tuple("(") + str + CodeColors.tuple(")");
        }

    }

    // A type declaration introduces a new type name, with a type instance and a parent.
    // The type Any has no parent, and is the root of the type hierarchy.
    // We do not print the case where the parent is Any, since it is the default.
    record Decl(String mod, String nm, Type ty, Type inst, Decl parent, String src) {

        @Override
        public String toString() {
            var ignore = nm.equals("Any") || this.parent.nm.equals("Any"); // parent is null for Any
            var snm = shortener.shorten(nm);
            return CodeColors.comment(snm + " ≡ ") + mod + " " + ty + (ignore ? "" : CodeColors.comment(" <: ") + inst);
        }

        public boolean isAbstract() {
            return mod.contains("abstract") || mod.contains("missing");
        }
    }

    record Sig(String nm, Type ty, String src) {

        boolean isGround() {
            if (ty instanceof Tuple t) {
                for (var typ : t.tys) {
                    if (typ instanceof Inst inst) {
                        if (!isConcrete(inst.nm())) {
                            return false;
                        }
                    } else if (typ instanceof Exist) {
                        return false; // not ground
                    } else if (typ instanceof Union) {
                        return false; // not ground either
                    } else if (typ instanceof Var) {
                        return false; // we should have rejected this earlier (at the exists)
                    } else if (typ instanceof Con) {
                        // ok
                    } else if (typ instanceof Tuple) {
                        return false; // not ground??
                    } else {
                        throw new RuntimeException("Unknown type: " + ty);
                    }
                }
                return true;
            } else {
                return false; // i.e. the method has an existential type, definitely not ground
            }
        }

        @Override
        public String toString() {
            return "function " + nm + " " + ty;
        }
    }

    GenDB db; // our database of types and signatures -- these are still not complete
    HashMap<String, InhNode> index = new HashMap<>(); // Map from type names to corresponding node in the inheritance tree

    HashMap<String, Decl> decls = new HashMap<>(); // Map from type names to corresponding Decl. Final.
    HashMap<String, Sig> sigs = new HashMap<>(); // Map from function names to corresponding Sig. Final.
    HashMap<String, List<String>> directInheritance = new HashMap<>();

    static Generator self; // hack to let static methods to access the instance, we have a single generator

    Generator(GenDB db) {
        this.db = db;
        self = this;
    }

    public void gen() {
        for (var decl : db.tydb.values()) {
            index.put(decl.nm(), new InhNode(decl));
        }
        for (var n : index.values()) {
            n.fixUp();
        }
        var d = index.get("Any");
        build(d);
        if (App.PRINT_HIERARCHY) {
            System.out.println("\nPrinting type hierarchy (in LIGHT color mode, RED means missing declaration, GREEN means abstract )");
            printHierarchy(d, 0);
        }
        for (var signodes : db.sigdb.values()) {
            for (var n : signodes) {
                try {
                    var s = new Sig(n.nm(), n.ty().toType(new ArrayList<>()), n.src());
                    sigs.put(n.nm(), s);
                    System.out.println(s);
                } catch (Exception e) {
                    System.err.println("Error: " + n.nm() + " " + e.getMessage());
                    System.err.println(CodeColors.comment("Failed at " + n.src()));
                }
            }
        }

        try {
            var w = new BufferedWriter(new FileWriter("test.jl"));
            for (var s : sigs.values()) {
                if (s.isGround()) {
                    System.err.println("Ground: " + s);
                    var str = ((Tuple) s.ty).tys.stream().map(Type::toString).collect(Collectors.joining(","));
                    var content = "code_warntype(" + s.nm + ",[" + str + "])\n";
                    w.write(content);

                }
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    // Is this a concrete type ? This can only be used once decls is populated in build().
    static boolean isConcrete(String nm) {
        return self.decls.containsKey(nm) && !self.decls.get(nm).isAbstract();
    }

    static class NameOrder implements Comparator<InhNode> {

        @Override
        public int compare(InhNode n1, InhNode n2) {
            return n1.name.compareTo(n2.name);
        }
    }

    void printHierarchy(InhNode n, int pos) {
        var str = n.d == null || n.d.isAbstract() ? CodeColors.abstractType(n.name) : n.name;
        str = n.d.mod().contains("missing") ? ("? " + CodeColors.abstractType(str)) : str;
        System.out.println(CodeColors.comment(".").repeat(pos) + str);
        n.children.sort(new NameOrder());
        directInheritance.put(n.name, n.children.stream().map(c -> c.name).collect(Collectors.toList()));
        for (var c : n.children) {
            printHierarchy(c, pos + 1);
        }
    }

    void build(InhNode n) {
        Decl d;
        try {
            d = n.toDecl();
            decls.put(n.name, d);
            System.out.println(d);
        } catch (Exception e) {
            System.err.println("Error: " + n.name + " " + e.getMessage());
            System.err.println("Failed at " + n.decl);
        }
        for (var c : n.children) {
            build(c);
        }
    }

    private final class InhNode {

        String name;
        GenDB.TyDecl decl;
        Decl d = null;
        InhNode parent = null;
        String parentName;
        List< InhNode> children = new ArrayList<>();

        InhNode(GenDB.TyDecl d) {
            this.decl = d;
            this.name = d.nm();
            this.parentName = ((GenDB.TyInst) d.parent()).nm();
            shortener.register(name);
        }

        void fixUp() {
            if (name.equals("Any")) {
                this.parent = this;
                return;
            }
            var pNode = index.get(parentName);
            assert pNode != null;
            this.parent = pNode;
            pNode.children.add(this);
        }

        Decl toDecl() {
            if (name.equals("Any")) {
                d = new Decl("abstract type", "Any", any, any, null, "");
            } else {
                var env = new ArrayList<Bound>();
                var t = decl.ty().toType(env);
                var inst = decl.parent().toType(getBounds(t, env));
                d = new Decl(decl.mod(), name, t, inst, parent.d, decl.src());
            }
            return d;
        }

        // Unwrap the existentials in the type, and return the bounds in the order they appear.
        // Called with a type declaration, so there should only be instances and existentials.
        private List<Bound> getBounds(Type t, List<Bound> env) {
            if (t instanceof Inst) {
                return env;
            } else if (t instanceof Exist ty) {
                env.addLast(ty.b());
                return getBounds(ty.ty(), env);
            }
            throw new RuntimeException("Unknown type: " + t);
        }
    }

    private final static Shortener shortener = new Shortener();

    // Some type names are long, we try to shorten them while ensuring that there is no ambiguity.
    // When there is ambiguity, we just use the full name. To do this, we make a pass over the database
    // and first observe all short names that are unique, and then use them.
    private final static class Shortener {

        HashMap<String, HashSet<String>> occurences = new HashMap<>();

        void register(String s) {
            var suffix = suffix(s);
            var occs = occurences.getOrDefault(suffix, new HashSet<>());
            occs.add(s);
            occurences.put(suffix, occs);
        }

        private String suffix(String s) {
            var i = s.lastIndexOf(".");
            return i == -1 ? s : s.substring(i + 1);
        }

        String shorten(String s) {
            if (App.SHORTEN) {
                var suffix = suffix(s);
                return occurences.get(suffix).size() == 1 ? suffix : s;
            } else {
                return s;
            }
        }
    }

}
