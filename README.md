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

    Results: Passed 100.0% of 1771 tests
          PASSED: 1771
          FAILED: 0
          STABLE: 1446
        UNSTABLE: 325

> TODO: there's a bug and some modules make it into the tests, eg. `isstablecall(Base, [])`. For now, these need to be commented out manually.
