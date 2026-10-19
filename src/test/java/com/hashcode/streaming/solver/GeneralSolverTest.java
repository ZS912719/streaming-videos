package com.hashcode.streaming.solver;

import com.hashcode.streaming.io.CheckedInputParser;
import com.hashcode.streaming.model.*;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import java.nio.file.*;
import java.util.*;

/** Differential tests for constructors, cooperative moves and incumbent retention. */
public final class GeneralSolverTest {
    public static void main(String[] args) throws Exception {
        Random random=new Random(194217);
        for(int sample=0;sample<50;sample++) {
            int v=4+random.nextInt(12),c=2+random.nextInt(4),e=3+random.nextInt(5);
            int[] sizes=new int[v];for(int i=0;i<v;i++)sizes[i]=1+random.nextInt(20);
            List<Endpoint> endpoints=new ArrayList<>();long total=0;
            for(int i=0;i<e;i++) {
                Endpoint endpoint=new Endpoint(100, c);
                for(int j=0;j<c;j++)if(random.nextBoolean())endpoint.addConnection(new CacheConnection(j,1+random.nextInt(70)));
                for(int j=0;j<v;j++)if(random.nextBoolean()){long n=1+random.nextInt(10000);endpoint.addRequest(j,n);total+=n;}
                endpoints.add(endpoint);
            }
            if(total==0)continue;
            ProblemInstance p=new ProblemInstance(v,e,c,25,sizes,endpoints,total);
            for(String kind:List.of("dynamic","static","zero")) {
                Solution expected=new DynamicOcagSolver().solve(p,kind.equals("zero")?0:1,true,kind.equals("static")).solution();
                Solution actual=BudgetedConstruction.construct(p,kind,Long.MAX_VALUE).solution;
                for(int cache=0;cache<c;cache++)if(!actual.videosInCache(cache).equals(expected.videosInCache(cache)))
                    throw new AssertionError("Constructor differs: "+sample+" "+kind);
            }
            Solution incumbent=new DynamicOcagSolver().solve(p);
            long original=PlacementState.fullRaw(p,incumbent);
            for(GeneralSolver.Mode mode:GeneralSolver.Mode.values()) {
                GeneralSolver.Settings settings=new GeneralSolver.Settings(mode,100000,20170317,true,true,
                        mode==GeneralSolver.Mode.COMPETITION,"all",true,350);
                GeneralSolver.Result result=new GeneralSolver().solve(p,settings,incumbent);
                new SolutionValidator().validate(p,result.solution());
                if(result.raw()<original||PlacementState.fullRaw(p,incumbent)!=original)throw new AssertionError("Incumbent regressed/mutated");
                long previous=original;
                for(GeneralSolver.Event event:result.trace()) {
                    if(event.bestRaw()<previous)throw new AssertionError("Best objective regressed");
                    previous=event.bestRaw();
                }
            }
            GeneralSolver.Settings fixed=new GeneralSolver.Settings(GeneralSolver.Mode.PORTFOLIO,100000,42,true,true,false,"all",true,200);
            GeneralSolver.Result first=new GeneralSolver().solve(p,fixed,null),second=new GeneralSolver().solve(p,fixed,null);
            if(first.raw()!=second.raw())throw new AssertionError("Fixed-work deterministic replay differs");
        }
        Path input=Path.of("tmp/checked-parser-test.in");Files.createDirectories(input.getParent());
        Files.writeString(input,"1 1 2 1 10\n1\n100 1\n0 1\n0 0 3000000000\n0 0 3000000000\n");
        ProblemInstance wide=new CheckedInputParser().parse(input);
        if(wide.totalRequestCount()!=6000000000L||wide.endpoints().get(0).requestsByVideo().get(0).requestCount()!=6000000000L)
            throw new AssertionError("Wide request aggregation failed");
        if(GeneralSolver.score(wide,594000000000L)!=99000)throw new AssertionError("Wide score failed");
        Files.writeString(input,"1 1 1 1 10\n1\n100 1\n0 1\n0 0 999999999999999999999\n");
        try{new CheckedInputParser().parse(input);throw new AssertionError("Overflow accepted");}catch(java.io.IOException expected){ }
        System.out.println("General solver: 150 constructor differential cases; 150 verified searches; fixed-work replay; wide aggregation and overflow rejection passed.");
    }
}
