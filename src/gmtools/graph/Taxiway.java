package gmtools.graph;


import java.util.ArrayList;
import java.util.List;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 *
 * <br/><br/> 
 * 
 * a collection of edges make up a single taxiway*/
public class Taxiway {
	public enum Type {TAXIWAY, RUNWAY}
	private List<TaxiEdge> edges;
	private List<TaxiNode> nodes;
	private String name;
	private Type type;
	
	public Taxiway(String name, Type type) {
		this.name = name;
		this.type = type;
		this.edges = new ArrayList<TaxiEdge>();
		this.nodes = new ArrayList<TaxiNode>();
	}
	
	public List<TaxiEdge> getEdges() {
		return this.edges;
	}
	
	public void addEdge(TaxiEdge edge) {
		this.edges.add(edge);
	}
	
	public void removeEdge(TaxiEdge edge) {
		this.edges.remove(edge);
	}
	
	public void replaceEdge(TaxiEdge toReplace, List<TaxiEdge> replacements) {
		int i = this.edges.indexOf(toReplace);
		this.edges.remove(i);
		for (TaxiEdge te : replacements) {
			this.edges.add(i++, te);
		}
	}
	
	public List<TaxiNode> getNodes() {
		return nodes;
	}
	
	public void addNode(TaxiNode node) {
		this.nodes.add(node);
	}
	
	public void removeNode(TaxiNode node) {
		this.nodes.remove(node);
	}
	
	public Type getType() {
		return type;
	}
	
	public String getName() {
		return this.name;
	}
}
