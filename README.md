In the root of this repo (e.g. `~/Projects/JuliaGenerable`):

Generate function and type records

    julia --project=./TypeDiscover.jl -e 'using TypeDiscover; typediscover(; funcfile="./Inputs/stdf.jlg", typefile="./Inputs/stdt.jlg")'

Build the Java project

    mvn -f ./Project/pom.xml package

Generate the test cases

    java -cp ./Project/target/JuliaGenerable2-1.0-SNAPSHOT.jar prlprg.App -c=NONE -r=./Inputs/ -f=stdf.jlg -t=stdt.jlg -h=FALSE -s=FALSE

Install dependencies in a shared Julia environment (this stays between runs and can be reused)

    julia ./StabilityChecker/shenv.jl ./pkgs.txt

And run the tests

    julia ./StabilityChecker/test.jl ./imports.jl ./tests.jl

If all goes well, you should see results such as

    Results: Passed 100.0% of 5473 tests
          PASSED: 5473
          FAILED: 0
          STABLE: 3608
        UNSTABLE: 1865

> TODO: there are some bugs in the generated tests:
>
> `isstablecall(Base, [])`
> `isstablecall(Core.Compiler, [])`
> `isstablecall(Core.Compiler.merge, [NamedTuple, NamedTuple{EOF}])` and such should replace EOFs with `()`
> `isstablecall(Base.iterate, [Base.Iterators.ProductIterator{()}])` should take `Tuple{}` not `()`
> `isstablecall(Core.Compiler.iterate, [Core.Compiler.Iterators.ProductIterator{()}])` same
> `isstablecall(Core.Compiler.lift_comparison!, [typeof(===), Core.Compiler.IncrementalCompact, Int64, Expr, Core.Compiler.IdDict{Pair{[Core.Compiler.NewSSAValue|Core.Compiler.OldSSAValue|Core.SSAValue],Any},[Core.Compiler.NewSSAValue|Core.Compiler.OldSSAValue|Core.SSAValue]}])` and such printing unions
>
> For now, these need to be commented out manually.
