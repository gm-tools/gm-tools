package gmtools.common;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class Sets {
	/**
	 * generates combinatorial sets from 0 to n-1 or order k; e.g. n=4,k=2: 01,02,03,12,13,23
	 * only works for up to a little under nChooseK < Integer.MAX_VALUE because of java arrays
	 * taken from http://stackoverflow.com/a/15603638
	 **/
	public static int[][] nChooseKSets(int n, int k) {
		// binomial(N, K)
		int c = (int)nChooseK(n, k);
		// where all sets are stored
		int[][] res = new int[c][Math.max(0, k)];
		// the k indexes (from set) where the red squares are
		// see image above
		int[] ind = k < 0 ? null : new int[k];
		// initialize red squares
		for (int i = 0; i < k; ++i) { ind[i] = i; }
		// for every combination
		for (int i = 0; i < c; ++i) {
			// get its elements (red square indexes)
			for (int j = 0; j < k; ++j) {
				res[i][j] = ind[j];
			}
			// update red squares, starting by the last
			int x = ind.length - 1;
			boolean loop;
			do {
				loop = false;
				// move to next
				ind[x] = ind[x] + 1;
				// if crossing boundaries, move previous
				if (ind[x] > n - (k - x)) {
					--x;
					loop = x >= 0;
				} else {
					// update every following square
					for (int x1 = x + 1; x1 < ind.length; ++x1) {
						ind[x1] = ind[x1 - 1] + 1;
					}
				}
			} while (loop);
		}
		return res;
	}
	
	/**how many ways can we choose k elements from n elements?*/
	public static long nChooseK(int n, int k) {
		if ((n < 0) || (k > n)) {
			throw new IllegalArgumentException();
		}
		
	    double rval = 1.0;
	    for (int i = 1; i <= k; i++) {
	    	rval *= (n + 1 - i);
	    	rval /= i;
	    }
	    
	    return (int)rval;
	}
}
