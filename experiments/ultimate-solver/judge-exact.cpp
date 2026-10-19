// Independent strict solution validation and 128-bit evaluation of extended counts.
// The supplied judge and its historical wide-integer copy are preserved separately.
#include <algorithm>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
using namespace std;
using Wide = unsigned __int128;

struct Request { int video, endpoint; long long count; };
int main(int argc, char** argv) {
    try {
        if (argc != 3) throw runtime_error("Usage: judge-exact input output");
        ifstream input(argv[1]), output(argv[2]);
        if (!input || !output) throw runtime_error("Cannot open input/output");
        auto integer = [&](long long low, long long high) {
            long long value;
            if (!(input >> value) || value < low || value > high) throw runtime_error("Invalid input integer");
            return value;
        };
        int V=integer(1,10000),E=integer(1,1000),R=integer(1,1000000),C=integer(1,1000),X=integer(1,500000);
        vector<int> sizes(V),dc(E);
        for (int& size:sizes) size=integer(1,1000);
        vector<vector<int>> links(E,vector<int>(C,-1));
        for (int e=0;e<E;e++) {
            dc[e]=integer(2,4000); int degree=integer(0,C);
            for (int j=0;j<degree;j++) {
                int c=integer(0,C-1),latency=integer(1,min(500,dc[e]-1));
                if (links[e][c]>=0) throw runtime_error("Duplicate input cache link");
                links[e][c]=latency;
            }
        }
        vector<Request> requests; requests.reserve(R);
        Wide total=0;
        for(int i=0;i<R;i++) {
            int v=integer(0,V-1),e=integer(0,E-1);
            long long count=integer(1,9223372036854775807LL);
            requests.push_back({v,e,count}); total+=static_cast<Wide>(count);
        }
        string extra;
        if(input>>extra)throw runtime_error("Trailing input data");
        string line;
        if(!getline(output,line))throw runtime_error("Missing output header");
        istringstream header(line); int used;
        if(!(header>>used)||used<0||used>C||(header>>extra))throw runtime_error("Invalid output header");
        vector<unsigned char> seen(C);
        vector<vector<unsigned char>> contains(C,vector<unsigned char>(V));
        vector<vector<int>> cachesByVideo(V);
        for(int i=0;i<used;i++) {
            if(!getline(output,line))throw runtime_error("Missing cache line");
            istringstream row(line); int c;
            if(!(row>>c)||c<0||c>=C||seen[c])throw runtime_error("Invalid/duplicate cache");
            seen[c]=1;long long usedSize=0;
            string token;
            while(row>>token) {
                if(token.find_first_not_of("0123456789")!=string::npos)throw runtime_error("Invalid video token");
                long long parsed=stoll(token);
                if(parsed<0||parsed>=V)throw runtime_error("Invalid video ID");
                int v=static_cast<int>(parsed);
                if(contains[c][v])throw runtime_error("Duplicate video");
                contains[c][v]=1; usedSize+=sizes[v];
                cachesByVideo[v].push_back(c);
            }
            if(!row.eof()||usedSize>X)throw runtime_error("Malformed row or capacity exceeded");
        }
        if(output>>extra)throw runtime_error("Trailing output data");
        Wide raw=0;
        for(const Request& request:requests) {
            int best=dc[request.endpoint];
            for(int c:cachesByVideo[request.video])if(links[request.endpoint][c]>=0)
                best=min(best,links[request.endpoint][c]);
            raw+=static_cast<Wide>(request.count)*(dc[request.endpoint]-best);
        }
        // With the checked dimensions/counts/latencies, even raw*1000 is <2^128.
        cout<<"Score = "<<static_cast<unsigned long long>(raw*1000/total)<<'\n';
        return 0;
    } catch(const exception& error) {
        cerr<<"Invalid: "<<error.what()<<'\n'; return 2;
    }
}
