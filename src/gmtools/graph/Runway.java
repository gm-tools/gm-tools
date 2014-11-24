package gmtools.graph;

import gmtools.common.Geography;
import gmtools.graph.TaxiNode.NodeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class Runway {
	private String name; // e.g. 05L/23R
	private String name1; // e.g. 05L
	private String name2; // e.g. 23R
	private Set<TaxiEdge> edges;
	private List<TaxiNode> entranceNodes1; // these are entrance nodes for 05L, also exit nodes for 23R, in order of increasing distance from the start of 05L
	private List<TaxiNode> entranceNodes2; // these are entrance nodes for 23R, also exit nodes for 05L, in order of increasing distance from the start of 23R
	private List<Double> distances1; // distance that each node in entranceNodes1 is from start of runway
	private List<Double> distances2; // distance that each node in entranceNodes2 is from start of runway
	
	public Runway(String name) {
		this.name = name;
		String[] split = name.split("[/\\\\-]");
		this.name1 = split[0].trim();
		this.name2 = split[1].trim();
		
		this.edges = new TreeSet<TaxiEdge>();
		this.entranceNodes1 = null;
		this.entranceNodes2 = null;
	}
	
	public void addEdge(TaxiEdge te) {
		this.edges.add(te);
	}
	
	public void determineEntranceNodes() {
		// figure out which nodes are at the ends
		// if this is a 00/18 RW, then the first node is the one with smallest latitude
		// otherwise, the first node is the one with the smallest longitude
		boolean compareLat = name.contains("00") || name.contains("18");
		
		this.entranceNodes1 = new ArrayList<TaxiNode>();
		this.entranceNodes2 = new ArrayList<TaxiNode>();
		this.distances1 = new ArrayList<Double>();
		this.distances2 = new ArrayList<Double>();
		
		double lowestValue = Double.POSITIVE_INFINITY;
		double highestValue = Double.NEGATIVE_INFINITY;
		TaxiNode end1 = null;
		TaxiNode end2 = null;
		for (TaxiEdge te : edges) {
			if ((compareLat ? te.getTnFrom().getLatCoordinate() : te.getTnFrom().getLonCoordinate()) < lowestValue) {
				end1 = te.getTnFrom();
				lowestValue = te.getTnFrom().getLonCoordinate();
			}
			if ((compareLat ? te.getTnFrom().getLatCoordinate() : te.getTnFrom().getLonCoordinate()) > highestValue) {
				end2 = te.getTnFrom();
				highestValue = te.getTnFrom().getLonCoordinate();
			}
			if ((compareLat ? te.getTnTo().getLatCoordinate() : te.getTnTo().getLonCoordinate()) < lowestValue) {
				end1 = te.getTnTo();
				lowestValue = te.getTnTo().getLonCoordinate();
			}
			if ((compareLat ? te.getTnTo().getLatCoordinate() : te.getTnTo().getLonCoordinate()) > highestValue) {
				end2 = te.getTnTo();
				highestValue = te.getTnTo().getLonCoordinate();
			}
		}
		
		// now, for each node, which end is it closest to?
		for (TaxiEdge te : edges) {
			if (te.getTnFrom().getNodeType() == NodeType.RUNWAY_CROSSING) {
				double tnFromDistance1 = Geography.distance(te.getTnFrom(), end1);
				double tnFromDistance2 = Geography.distance(te.getTnFrom(), end2);
				
				if (tnFromDistance1 < tnFromDistance2) {
					if (!entranceNodes1.contains(te.getTnFrom())) {
						entranceNodes1.add(te.getTnFrom());
						distances1.add(tnFromDistance1);
					}
				} else {
					if (!entranceNodes2.contains(te.getTnFrom())) {
						entranceNodes2.add(te.getTnFrom());
						distances2.add(tnFromDistance2);
					}
				}
			}
			
			if (te.getTnTo().getNodeType() == NodeType.RUNWAY_CROSSING) {
				double tnToDistance1 = Geography.distance(te.getTnTo(), end1);
				double tnToDistance2 = Geography.distance(te.getTnTo(), end2);
				
				if (tnToDistance1 < tnToDistance2) {
					if (!entranceNodes1.contains(te.getTnTo())) {
						entranceNodes1.add(te.getTnTo());
						distances1.add(tnToDistance1);
					}
				} else {
					if (!entranceNodes2.contains(te.getTnTo())) {
						entranceNodes2.add(te.getTnTo());
						distances2.add(tnToDistance2);
					}
				}
			}
		}
		
		// finally, sort the lists of nodes
		for (int i = 0; i < entranceNodes1.size(); i++) {
			for (int j = 0; j < entranceNodes1.size(); j++) {
				if (i != j) {
					if (distances1.get(j) > distances1.get(i)) {
						Collections.swap(entranceNodes1, i, j);
						Collections.swap(distances1, i, j);
					}
				}
			}
		}
		for (int i = 0; i < entranceNodes2.size(); i++) {
			for (int j = 0; j < entranceNodes2.size(); j++) {
				if (i != j) {
					if (distances2.get(j) > distances2.get(i)) {
						Collections.swap(entranceNodes2, i, j);
						Collections.swap(distances2, i, j);
					}
				}
			}
		}
	} // end of determineEntranceNodes() method
	
	public void removeNodes(Collection<TaxiNode> nodes) {
		TreeSet<TaxiEdge> toRemove = new TreeSet<>();
		for (TaxiEdge e : this.edges) {
			if (nodes.contains(e.getTnFrom()) || nodes.contains(e.getTnTo())) {
				toRemove.add(e);
			}
		}
		this.edges.removeAll(toRemove);
		
		for (int i = this.entranceNodes1.size() - 1; i >= 0; i--) {
			if (nodes.contains(this.entranceNodes1.get(i))) {
				this.entranceNodes1.remove(i);
				this.distances1.remove(i);
			}
		}
		
		for (int i = this.entranceNodes2.size() - 1; i >= 0; i--) {
			if (nodes.contains(this.entranceNodes2.get(i))) {
				this.entranceNodes2.remove(i);
				this.distances2.remove(i);
			}
		}
	}
	
	public String getName() {
		return name;
	}
	
	public String getName1() {
		return name1;
	}
	
	public String getName2() {
		return name2;
	}
	
	public List<TaxiNode> getEntranceNodes1() {
		return entranceNodes1;
	}
	
	public List<TaxiNode> getEntranceNodes2() {
		return entranceNodes2;
	}
	
	public Set<TaxiEdge> getEdges() {
		return edges;
	}
} // end of runway subclass