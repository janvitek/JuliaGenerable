package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

class Generator {

    GenDB db;
    HashMap<String, InhNode> index = new HashMap<>();

    public Generator(GenDB db) {
        this.db = db;
        var value = new InhNode(new TyDecl("Any", Ty.any(), Ty.none(), "none"));
        this.index.put("Any", value);
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

        printHierarchy(index.get("Any"), 0, false);

    }

    void printHierarchy(InhNode n, int pos, boolean any) {
        if (n.name.equals("Any") && any) {
            System.err.println("Seeing any agains");
            return;
        }
        System.out.println(Color.red(".").repeat(pos + 1) + n.name);
        for (var c : n.children) {
            printHierarchy(c, pos + 1, true);
        }
    }

    class InhNode {

        String name;
        TyDecl decl;
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
