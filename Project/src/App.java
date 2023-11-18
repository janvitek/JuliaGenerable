public class App {

    public static void main(String[] args) {
        var parser = new Parser("../Inputs/types.jlg");
        while (parser.peek() != null) {
            var typ = TypeDeclaration.parse(parser);
            if (typ == null)
                break;
            System.out.println(typ);
        }
        // var methodParser = new Parser("../functions.jlg");
     //   while (true) {
            // var method = null;// methodParser.parseMethod(); if (method == null) break;
            // System.out.println(method);
    //    }
    }
}