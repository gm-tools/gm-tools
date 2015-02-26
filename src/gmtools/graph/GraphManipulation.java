package gmtools.graph;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class GraphManipulation {
	/**@return coords as 2D array, with lon,lat ordering - these are coords marking the midpoint of each edge*/
	public static double[][] edgeListToMidpointCoordList(List<TaxiEdge> edges) {
		double[][] coords = new double[edges.size()][2];
		
		for (int i = 0; i < edges.size(); i++) {
			double[] mp = edges.get(i).getMidpoint();
			coords[i][0] = mp[1];
			coords[i][1] = mp[0];
		}
		
		return coords;
	}

	public static Map<String, Runway> locateRunways(Collection<TaxiEdge> edges) {
		Map<String, Runway> runways = new TreeMap<String, Runway>();
		
		for (TaxiEdge te : edges) {
			if (te.getEdgeType() == TaxiEdge.EdgeType.RUNWAY) {
				String name = te.getMeta();
				
				if ((name != null) && !name.isEmpty()) {
					Runway runway = runways.get(name);
					if (runway == null) {
						runway = new Runway(name);
						runways.put(name, runway);
					}
					runway.addEdge(te);
				}
			}
		}
		
		for (Runway r : runways.values()) {
			r.determineEntranceNodes();
		}
		
		return runways;
	}
}
