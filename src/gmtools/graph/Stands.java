package gmtools.graph;

import java.util.Map;
import java.util.TreeMap;

import gmtools.graph.TaxiNode.NodeType;
import gmtools.parsers.SpecifiedGateLocations;
import gmtools.parsers.SpecifiedGateLocations.Stand;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class Stands {
	private Map<String, TaxiNode> standNodes = new TreeMap<String, TaxiNode>();
	private Map<String, Stand> standsWithNoCoords;
	
	public Stands(String filename) {
		this.standNodes = new TreeMap<String, TaxiNode>();
		this.standsWithNoCoords = new TreeMap<String, Stand>();

		for (Stand s : SpecifiedGateLocations.loadStandsFromFile(filename)) {
			if (s.hasCoords()) {
				TaxiNode tn = new TaxiNode("S-" + s.getName(), NodeType.STAND, s.getLat(), s.getLon());
				tn.setAssociatedTaxiways(s.getAssociatedTaxiways());
				tn.setMeta(s.getName());
				tn.setNodeAttachment(s.getNodeAttachment());
				standNodes.put(s.getName(), tn);
			} else {
				standsWithNoCoords.put(s.getName(), s);
			}
		}
	}
	
	public Map<String, TaxiNode> getStandNodes() {
		return standNodes;
	}
	
	public Map<String, Stand> getStandsWithNoCoords() {
		return standsWithNoCoords;
	}
}