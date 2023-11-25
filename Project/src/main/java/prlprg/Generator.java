package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

class Generator {

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

    //The instance of a type constructor can have type parameters, examples are: Int32 and Vector{Int,N}.
    // When occuring on the left hand side of a type declaration, an instance can only have bound variables.
    // When occuring on the right hand side of a type declaration an instance can have a mix of types
    // and variables (bound on the LHS of the <:).
    // When generating results, the type instance should not have free variables.
    // Furthermore, we expect that the constructor name is defined in the type db.
    record Inst(String nm, List<Type> tys) implements Type {

        @Override
        public String toString() {
            var args = tys.stream().map(Type::toString).collect(Collectors.joining(","));
            return nm + (tys.isEmpty() ? "" : "{" + args + "}");
        }
    }

    // Each variable occurence, must refer to a bound in an enclosing \Exist. At this point, we should have
    // rejected types with free variables as ill-formed.
    record Var(Bound b) implements Type {

        @Override
        public String toString() {
            return Color.yellow(b.nm());
        }
    }

    // A bound introduces a variable, with an upper and a lower bound. Julia allows writing inconsistent bounds,
    // i.e. !(low <: up). These are meaningless types which cannot be used. We do not check this. We do check
    // that the types are well-formed (no undefined constructor and no free variables)
    record Bound(String nm, Type low, Type up) {

        @Override
        public String toString() {
            return (!isNone(low) ? low + "<:" : "") + Color.yellow(nm) + (!isAny(up) ? "<:" + up : "");
        }
    }

    // A constant, such as a number, character or string. The implementation of the parser does not attempt
    // do much we constant, they are treated as uninterpreted strings.
    record Con(String nm) implements Type {

        @Override
        public String toString() {
            return nm;
        }
    }

    record Exist(Bound b, Type ty) implements Type {

        @Override
        public String toString() {
            return Color.red("∃") + b + Color.red(".") + ty;
        }
    }

    record Union(List<Type> tys) implements Type {

        @Override
        public boolean equals(Object o) {
            if (o instanceof Union u) {
                return tys.equals(u.tys);
            }
            return false;
        }

        @Override
        public String toString() {
            return "[" + tys.stream().map(Type::toString).collect(Collectors.joining(Color.red("|"))) + "]";
        }
    }

    record Tuple(List<Type> tys) implements Type {

        @Override
        public String toString() {
            return "(" + tys.stream().map(Type::toString).collect(Collectors.joining(",")) + ")";
        }
    }

    // A type declaration introduces a new type name, with a type instance and a parent.
    // The type Any has no parent, and is the root of the type hierarchy.
    // We do not print the case where the parent is Any, since it is the default.
    record Decl(String nm, Type ty, Type inst, Decl parent, String src) {

        @Override
        public String toString() {
            var ignore = this.parent == null || this.parent.nm.equals("Any");
            return "type " + nm + " ≡ " + ty + (ignore ? "" : " <: " + inst);
        }
    }

    record Sig(String nm, Type ty, String src) {

        @Override
        public String toString() {
            return "function " + nm + " " + ty;
        }
    }

    int gen = 0;

    Type toType(Ty t, List<Bound> env) {
        return switch (t) {
            case TyInst ty ->
                new Inst(ty.nm(), ty.tys().stream().map(tt -> toType(tt, env)).collect(Collectors.toList()));
            case TyVar ty ->
                new Var(env.reversed().stream().filter(b -> b.nm().equals(ty.nm())).findFirst().orElseThrow());
            case TyCon ty ->
                new Con(ty.nm());
            case TyUnion ty ->
                new Union(ty.tys().stream().map(tt -> toType(tt, env)).collect(Collectors.toList()));
            case TyTuple ty ->
                new Tuple(ty.tys().stream().map(tt -> toType(tt, env)).collect(Collectors.toList()));
            case TyExist ty -> {
                String name;
                Type low;
                Type up;
                if (ty.v() instanceof TyVar tvar) {
                    name = tvar.nm();
                    name = name.equals("???") ? "t" + gen++ : name;
                    low = toType(tvar.low(), env);
                    up = toType(tvar.up(), env);
                } else {
                    var inst = (TyInst) ty.v();
                    if (!inst.tys().isEmpty()) {
                        throw new RuntimeException("Should be a TyVar but is a type: " + ty);
                    }
                    name = inst.nm();
                    low = none;
                    up = any;
                }
                var b = new Bound(name, low, up);
                var newenv = new ArrayList<>(env);
                newenv.add(b);
                yield new Exist(b, toType(ty.ty(), newenv));
            }
            default ->
                throw new RuntimeException("Unknown type: " + t);
        };
    }

    // Unwrap the existentials in the type, and return the bounds in the order they appear.
    // Called with a type declaration, so there should only be instances and existentials.
    List<Bound> getBounds(Type t, List<Bound> env) {
        assert t != null;
        if (t instanceof Inst) {
            return env;
        }
        var ty = (Exist) t;
        env.addLast(ty.b());
        return getBounds(ty.ty(), env);
    }

    Decl toDecl(InhNode n) {
        var env = new ArrayList<Bound>();
        var t = toType(n.decl.ty(), env);
        var inst = toType(n.decl.parent(), getBounds(t, env));
        var p = n.parent == null ? null : n.parent.d;
        return new Decl(n.name, t, inst, p, n.decl.src());
    }

    Sig toSig(TySig n) {
        var env = new ArrayList<Bound>();
        var t = toType(n.ty(), env);
        return new Sig(n.nm(), t, n.src());
    }

    GenDB db; // our database of types and signatures
    // The index is a map from type names to the corresponding node in the inheritance tree.
    HashMap<String, InhNode> index = new HashMap<>();

    public Generator(GenDB db) {
        this.db = db;
    }

    void processTypes() {
        for (var decl : db.tydb.values()) {
            var node = new InhNode(decl);
            index.put(node.name, node);
        }
        for (var node : index.values()) {
            var pNode = index.get(node.parentName);
            if (pNode == null) {
                System.err.println("Warning: " + node.name + " has no parent " + node.parentName);
                continue;
            }
            node.parent = pNode;
            pNode.children.add(node);
        }

        var d = index.get("Any");
        printHierarchy(d, 0);
        build(d);
    }

    void processSigs() {
        for (var signodes : db.sigdb.values()) {
            for (var signode : signodes) {
                try {
                    var s = toSig(signode);
                    System.out.println(s);
                } catch (Exception e) {
                    System.err.println("Error: " + signode.nm() + " " + e.getMessage());
                    System.err.println(Color.green("Failed at " + signode.src()));
                }
            }
        }
    }

    void printHierarchy(InhNode n, int pos) {
        System.out.println(Color.red(".").repeat(pos) + n.name);
        for (var c : n.children) {
            printHierarchy(c, pos + 1);
        }
    }

    void build(InhNode n) {
        Decl d;
        try {
            d = toDecl(n);
            System.out.println(d);
        } catch (Exception e) {
            System.err.println("Error: " + n.name + " " + e.getMessage());
            System.err.println("Failed at " + n.decl);
        }
        for (var c : n.children) {
            build(c);
        }
    }

    class InhNode {

        String name;
        TyDecl decl;
        Decl d = null;
        InhNode parent = null;
        String parentName;
        List< InhNode> children = new ArrayList<>();

        public InhNode(TyDecl d) {
            this.decl = d;
            this.name = d.nm();
            this.parentName = findName(d.parent());
        }
    }

    String findName(Ty ty) {
        if (ty instanceof TyInst t) {
            return t.nm();
        }
        if (ty instanceof TyUnion t) {
            return "None";
        }
        throw new RuntimeException("Unknown type: " + ty);
    }
}
