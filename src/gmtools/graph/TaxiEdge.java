package gmtools.graph;


import gmtools.common.GroundMovementWriter;
import gmtools.graph.TaxiNode.NodeType;

import org.jgrapht.graph.DefaultWeightedEdge;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class TaxiEdge extends DefaultWeightedEdge implements Comparable<TaxiEdge> {
	/** generated by eclipse */
	private static final long serialVersionUID = -6912691060638551129L;
	private static int SEQ_NO = 0;
	
	public enum EdgeType { TAXIWAY, STAND_CONNECTION, /**actually a runway, not for taxiing!*/RUNWAY }
	
	private String id;
	private Taxiway taxiway;
	private double length;
	private TaxiNode tnFrom;
	private TaxiNode tnTo;
	private double[] traversalTimes;
	private int seqNo;
	private EdgeType edgeType;
	private String meta; // runway name if it applies
	
	public TaxiEdge(String id, Taxiway taxiway, TaxiNode tnFrom, TaxiNode tnTo, double length, EdgeType type) {
		this.id = id;
		this.seqNo = SEQ_NO++;
		this.taxiway = taxiway;
		this.length = length;
		this.tnFrom = tnFrom;
		this.tnTo = tnTo;
		this.edgeType = type;
		this.meta = "";
	}
	
	public String getId() {
		return id;
	}
	
	public void setId(String id) {
		this.id = id;
	}

	public double getLength() {
		return length;
	}

	public double[] getTraversalTimes() {
		return traversalTimes;
	}

	public void setTraversalTimes(double[] traversalTimes) {
		this.traversalTimes = traversalTimes;
	}
	
	public TaxiNode getTnFrom() {
		return tnFrom;
	}
	
	public TaxiNode getTnTo() {
		return tnTo;
	}
	
	public EdgeType getEdgeType() {
		return edgeType;
	}
	
	public boolean shouldHaveIntermediatesAdded() {
		return this.edgeType == EdgeType.TAXIWAY;
	}
	
	/**@return a string to uniquely identify this edge - it will at least incorporate the seqNo which is unique per TaxiEdge object*/
	public String getUniqueString() {
		return "SN" + seqNo + ((taxiway != null) ? "-TW[" + this.taxiway.getName() + "]" : "") + ((meta != null) && (!meta.isEmpty()) ? "-(" + meta + ")" : "");
	}
	
	public double[] getMidpoint() {
		double lat = (this.tnFrom.getLatCoordinate() + this.tnTo.getLatCoordinate()) / 2.0;
		double lon = (this.tnFrom.getLonCoordinate() + this.tnTo.getLonCoordinate()) / 2.0;
		return new double[] {lat, lon};
	}
	
	public boolean isAdjacentTo(TaxiEdge te) {
		return containsNode(te.getTnFrom()) || containsNode(te.getTnTo());
	}
	
	public boolean containsNode(TaxiNode tn) {
		return (this.tnFrom == tn) || (this.tnTo == tn);
	}
	
	public boolean containsRunwayOrRunwayConnectionNode() {
		return (this.tnFrom.getNodeType() == NodeType.RUNWAY) || (this.tnFrom.getNodeType() == NodeType.RUNWAY_CROSSING) || (this.tnTo.getNodeType() == NodeType.RUNWAY) || (this.tnTo.getNodeType() == NodeType.RUNWAY_CROSSING);
	}
	
	public String getMeta() {
		return meta;
	}
	
	public void setMeta(String meta) {
		this.meta = meta;
	}
	
	/**@return null if not a stand edge*/
	public String getStandName() {
		if (this.edgeType == EdgeType.STAND_CONNECTION) {
			// name should be in "meta" - but if not, look at the nodes too as they may have it instead
			if (this.meta != null) {
				return this.meta;
			} else if (this.tnFrom.getNodeType() == NodeType.STAND) {
				return this.tnFrom.getMeta();
			} else if (this.tnTo.getNodeType() == NodeType.STAND) {
				return this.tnTo.getMeta();
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	@Override
	public String toString() {
		return "Edge[" + getUniqueString() + "-ID" + this.id + "-" + this.edgeType + " " + this.tnFrom + ">" + this.tnTo + "]";
	}

	@Override
	public int compareTo(TaxiEdge that) {
		return this.seqNo - that.seqNo;
	}
	
	public static EdgeType gmSpecificationToType(GroundMovementWriter.Edge.Specification spec) {
		switch (spec) {
		case gate:
			return EdgeType.STAND_CONNECTION;
		case runway:
			return EdgeType.RUNWAY;
		case taxiway:
			return EdgeType.TAXIWAY;
		default: //runwaytaxiway, other
			return null;
		}
	}
}
