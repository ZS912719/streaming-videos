package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.*;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.scoring.SolutionValidator;
import java.util.*;

/** Independent full rescoring after every accepted V2 move, plus exhaustive knapsack checks. */
public final class UltimateV2Test {
    public static void main(String[] args) {
        Random random=new Random(207113);
        int runs=0;
        for(int sample=0;sample<100;sample++) {
            int v=5+random.nextInt(10),c=1+random.nextInt(5),e=2+random.nextInt(5);
            int[] sizes=new int[v];for(int i=0;i<v;i++)sizes[i]=1+random.nextInt(20);
            List<Endpoint> endpoints=new ArrayList<>();long total=0;
            for(int i=0;i<e;i++) {
                Endpoint ep=new Endpoint(100,c);
                for(int j=0;j<c;j++)if(random.nextBoolean())ep.addConnection(new CacheConnection(j,1+random.nextInt(90)));
                for(int j=0;j<v;j++)if(random.nextBoolean()){int n=1+random.nextInt(100);ep.addRequest(j,n);total+=n;}
                endpoints.add(ep);
            }
            if(total==0)continue;
            ProblemInstance p=new ProblemInstance(v,e,c,25,sizes,endpoints,total);
            Solution incumbent=new DynamicOcagSolver().solve(p);
            long original=PlacementState.fullRaw(p,incumbent);
            PlacementState indexState=new PlacementState(p,GeneralSolver.copy(p,incumbent));
            BenefitIndex index=new BenefitIndex(indexState);
            long[] actual=new long[v],expected=new long[v];
            for(int mutation=0;mutation<30;mutation++) {
                int cache=random.nextInt(c),video=random.nextInt(v);
                if(indexState.has(cache,video))indexState.remove(cache,video);
                else if(indexState.used[cache]+sizes[video]<=p.cacheCapacity())indexState.add(cache,video);
                indexState.refresh(video);index.refresh(video);
                int row=random.nextInt(c);index.row(row,actual);indexState.benefits(row,expected);
                if(!Arrays.equals(actual,expected))throw new AssertionError("Lazy benefit row differs after mutation");
                index.verify();
            }
            for(UltimateV2Solver.Policy policy:UltimateV2Solver.Policy.values()) {
                var settings=new UltimateV2Solver.Settings(policy,100000,20170317,true,20000,true,true,true,true,true,true,true);
                var result=new UltimateV2Solver().solve(p,settings,incumbent,(seconds,snapshot,raw,observed,early)->{
                    new SolutionValidator().validate(p,snapshot);
                    if(PlacementState.fullRaw(p,snapshot)!=raw)throw new AssertionError("Timed checkpoint mismatch");
                },9_999_000_000L);
                new SolutionValidator().validate(p,result.solution());
                if(result.raw()<original||PlacementState.fullRaw(p,incumbent)!=original)throw new AssertionError("Incumbent changed");
                long previous=original;
                for(var event:result.trace()){if(event.bestRaw()<previous)throw new AssertionError("Checkpoint regressed");previous=event.bestRaw();}
                var replay=new UltimateV2Solver().solve(p,settings,incumbent);
                if(replay.raw()!=result.raw())throw new AssertionError("Fixed-work replay differs");
                if(policy==UltimateV2Solver.Policy.FIXED) {
                    var unindexed=new UltimateV2Solver.Settings(policy,100000,20170317,true,20000,true,true,true,true,true,false,false);
                    var control=new UltimateV2Solver().solve(p,unindexed,incumbent);
                    for(int cache=0;cache<c;cache++)if(!control.solution().videosInCache(cache).equals(result.solution().videosInCache(cache)))
                        throw new AssertionError("Index/pruning changed fixed-work placement or ties");
                }
                runs++;
            }
        }
        for(int sample=0;sample<80;sample++) {
            int v=6+random.nextInt(8),capacity=10+random.nextInt(100);
            int[] sizes=new int[v];Endpoint ep=new Endpoint(100,1);ep.addConnection(new CacheConnection(0,1));long total=0;
            for(int i=0;i<v;i++){sizes[i]=1+random.nextInt(25);int n=1+random.nextInt(500);total+=n;ep.addRequest(i,n);}
            ProblemInstance p=new ProblemInstance(v,1,1,capacity,sizes,List.of(ep),total);
            long optimum=0;
            for(int mask=0;mask<(1<<v);mask++) {
                int size=0;long raw=0;
                for(int i=0;i<v;i++)if((mask&(1<<i))!=0){size+=sizes[i];raw+=ep.requestsByVideo().get(i).requestCount()*99;}
                if(size<=capacity)optimum=Math.max(optimum,raw);
            }
            for(boolean complement:List.of(false,true)) {
                var settings=new UltimateV2Solver.Settings(UltimateV2Solver.Policy.FIXED,100000,42,true,20000,false,true,false,complement,true,true,true);
                var result=new UltimateV2Solver().solve(p,settings,new Solution(1));
                if(result.raw()!=optimum)throw new AssertionError("Knapsack differs from exhaustive optimum");
            }
        }
        System.out.println("V2: "+runs+" verified searches/replays; "+(runs/3)+" exact index/pruning placement controls; "+(runs*10)+" benefit-index mutations; 160 exhaustive one-cache comparisons; incumbent/checkpoint preservation passed.");
    }
}
