package gmtools.graph;

import gmtools.common.GroundMovementWriter;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class TaxiNode implements Comparable<TaxiNode> {
	public enum NodeType { 
		STAND, 
		/** crossing point of >1 taxiways (aircraft maybe can't make all the transitions though - need to track this elsewhere) */ INTERSECTION, 
		/** intersection of a runway and possible multiple taxiways (may also be acess to runway) */ RUNWAY_CROSSING, 
		/** an intermediate point on a single taxiway, with no option for transfer to another taxiway/runway */ INTERMEDIATE,
		/** simply a coord to mark out a runway */ RUNWAY
	}
	
	private String id;
	private NodeType nodeType;
	private double latCoordinate;
	private double lonCoordinate;
	
	/**this holds the name of the stand or runway if such things apply to this node*/
	private String meta;
	
	/**this only applies to stand/gate nodes - it's the taxiways that the node are connected to*/
	private String[] associatedTaxiways;
	
	/**this also only applies to stand/gate nodes - specifies a specific node to attach to*/
	private String nodeAttachment;
	
	public TaxiNode(String id, NodeType nodeType, double latCoordinate, double lonCoordinate) {
		this.id = id;
		this.nodeType = nodeType;
		this.latCoordinate = latCoordinate;
		this.lonCoordinate = lonCoordinate;
		this.associatedTaxiways = new String[0];
		this.meta = "";
		this.nodeAttachment = null;
	}
	
	public TaxiNode(String id, NodeType nodeType) {
		this(id, nodeType, 0, 0);
	}
	
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public NodeType getNodeType() {
		return nodeType;
	}

	public void setNodeType(NodeType nodeType) {
		this.nodeType = nodeType;
	}

	public double getLatCoordinate() {
		return latCoordinate;
	}

	public void setLatCoordinate(double latCoordinate) {
		this.latCoordinate = latCoordinate;
	}

	public double getLonCoordinate() {
		return lonCoordinate;
	}

	public void setLonCoordinate(double lonCoordinate) {
		this.lonCoordinate = lonCoordinate;
	}
	
	public String[] getAssociatedTaxiways() {
		return associatedTaxiways;
	}
	
	public void setAssociatedTaxiways(String[] associatedTaxiways) {
		this.associatedTaxiways = associatedTaxiways;
	}
	
	public String getNodeAttachment() {
		return nodeAttachment;
	}
	
	public void setNodeAttachment(String nodeAttachment) {
		this.nodeAttachment = nodeAttachment;
	}
	
	public void setMeta(String meta) {
		this.meta = meta;
	}
	
	public String getMeta() {
		return meta;
	}

	@Override
	public String toString() {
		return "Node[" + nodeType + "-" + id + "(" + this.meta + ")]";
	}

	@Override
	public int compareTo(TaxiNode that) {
		return this.id.compareTo(that.id);
	}
	
	public static NodeType gmSpecificationToNodeType(GroundMovementWriter.Node.Specification spec) {
		switch (spec) {
		case gate:
			return NodeType.STAND;
		case holding_point:
			return NodeType.INTERMEDIATE;
		case intermediate:
			return NodeType.INTERMEDIATE;
		default: //case GroundMovementWriter.Node.Specification.runway:
			return NodeType.RUNWAY;
		}
	}
}
