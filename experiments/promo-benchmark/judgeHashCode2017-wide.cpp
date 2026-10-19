#include <cstdio>
#include <iostream>
#include <vector>

#define NB_CACHES 1000

using namespace std;

typedef struct en {
  int datacenter;
  int nbCaches;
  int* cachesLatency;
} endpoint;

typedef struct req {
  int video;
  int endpoint;
  int nb;
} request;

typedef struct vid {
  int nb;
  int caches[NB_CACHES];
} video;

int distance(int x, int y, int a, int b)
{
  return (abs(y - b) + abs(x - a));
}

int mauvaiseEntree() {
  cout << "Score = " << 0 << endl;
  return 0;
}

int main(int argc, char *argv[]) {
  if (argc < 3) {
    cout << "USAGE: ./judge input output" << endl
         << "With:" << endl;
    cout << "  - input: the filename in which there is the instance," << endl;
    cout << "  - output: the filename in which there is the solution." << endl;
    cout << "example: ./judge testcases/a_example.txt testcases/a_example.out" << endl;
    exit(0);
  }

  FILE *input = fopen(argv[1], "r");
  if (input == NULL) {
    cout << "Cannot open file " << argv[1] << endl;
    exit(0);
  }

  FILE *output = fopen(argv[2], "r");
  if (output == NULL) {
    cout << "Cannot open file " << argv[2] << endl;
    exit(0);
  }

  /* Parse data file */
  int nbVideos, nbEndpoints, nbRequest, nbCaches, capacity;
  fscanf(input, "%d %d %d %d %d", &nbVideos, &nbEndpoints, &nbRequest, &nbCaches, &capacity);

  int* size = (int*)calloc(nbVideos, sizeof(int));
  for (int i = 0; i < nbVideos; i++) {
    fscanf(input, "%d", size+i);
  }

  endpoint* endpoints = (endpoint*)malloc(nbEndpoints*sizeof(endpoint));
  for (int i = 0; i < nbEndpoints; i++) {
    int l, c;
    fscanf(input, "%d %d", &l, &c);
    endpoints[i].nbCaches = c;
    endpoints[i].datacenter = l;
    endpoints[i].cachesLatency = (int*)calloc(NB_CACHES, sizeof(int));
    for (int j = 0; j < endpoints[i].nbCaches; j++) {
      fscanf(input, "%d %d", &c, &l);
      endpoints[i].cachesLatency[c] = l;
    }
  }

  request* requests = (request*)malloc(nbRequest*sizeof(request));
  for (int i = 0; i < nbRequest; i++) {
    int v, e, n;
    fscanf(input, "%d %d %d", &v, &e, &n);
    requests[i].video = v;
    requests[i].endpoint = e;
    requests[i].nb = n;
  }
  /* End parsing data file */

  /* Parse and check solution */
  int nbUsedCache;
  video* solution = (video*)malloc(nbVideos*sizeof(video));
  for (int i = 0; i < nbVideos; i++) solution[i].nb = 0;
  fscanf(output, "%d", &nbUsedCache);
  if (nbUsedCache > nbCaches) return mauvaiseEntree();
  short *usedCache = (short *)calloc(nbCaches, sizeof(short));
  for (int i = 0; i < nbUsedCache; i++) {
    int totalSize = 0;
    int c, v;
    char r;
    int nbread = fscanf(output, "%d", &c);
    if (c >= nbCaches) return mauvaiseEntree();
    if (usedCache[c]) return mauvaiseEntree();
    usedCache[c] = 1;
    fscanf(output, "%c", &r);
    while (r != '\n') {
      fscanf(output, "%d", &v);
      if (v >= nbVideos) return mauvaiseEntree();
      solution[v].caches[solution[v].nb] = c;
      solution[v].nb += 1;
      totalSize += size[v];
      fscanf(output, "%c", &r);
    }
    if (totalSize > capacity) return mauvaiseEntree();
  }
  /* End parsing solution */

  /* Compute score */
  long long score = 0;
  long long totalRequest = 0;
  for (int i = 0; i < nbRequest; i++) {
    int endpoint = requests[i].endpoint;
    int video = requests[i].video;
    int dataLatency = endpoints[endpoint].datacenter;
    int minLatency = dataLatency;
    totalRequest +=requests[i].nb;

    for (int j = 0; j <  solution[video].nb; j++) {
      int cache = solution[video].caches[j];
      int latency = endpoints[endpoint].cachesLatency[cache];
      if (latency > 0) minLatency = min(minLatency, latency);
    }

    score += 1LL*requests[i].nb*(dataLatency - minLatency);
  }

  cout << "Score = " << score*1000/totalRequest << endl;
  return 0;
}
