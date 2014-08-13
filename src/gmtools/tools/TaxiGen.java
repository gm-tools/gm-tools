package gmtools.tools;

import gmtools.common.GroundMovementWriter;
import gmtools.common.Legal;
import gmtools.common.Maths;
import gmtools.graph.TaxiEdge;
import gmtools.graph.TaxiNode;
import gmtools.graph.TaxiNode.NodeType;
import gmtools.graph.Taxiway;
import gmtools.parsers.ParseOSM;
import gmtools.parsers.ParseOSM.AeroWay;
import gmtools.parsers.ParseOSM.AeroWay.Type;
import gmtools.parsers.SpecifiedGateLocations;
import gmtools.parsers.SpecifiedGateLocations.Stand;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jgrapht.graph.WeightedMultigraph;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;

import uk.me.jstott.jcoord.LatLng;
import uk.me.jstott.jcoord.UTMRef;
import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Icon;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.KmlFactory;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Style;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 *
 * <br/><br/>
 * This class will take OSM data and specified gate locations and generate a graph representation that is about right
 */
public class TaxiGen {
	private static final double DISTANCE_THRESHOLD_FOR_ZERO_M = 0.1; // equal LatLng value can return distances that are non-zero

	/**how far from an existing node on an edge should we be before making a new one?*/
	private double thresholdForSnapToNode;
	
	/**spacing for intermediate nodes*/
	private double spacingForIntermediates;
	
	/**the set of all edges, including runways, taxiways and stand connections*/
	private Set<TaxiEdge> allEdges;

	/**all nodes on the graph, including stands, runways, taxiway intersections and intermediate points*/
	private Map<String,TaxiNode> allNodes;

	/**all nodes marking stands, indexed by stand name*/
	private Map<String, TaxiNode> standNodes;

	/**runway objects - each contains a list of edges representing a runway; indexed by runway name e.g. 06/30*/
	private Map<String, Runway> runways;

	/**the set of all edges suitable for taxiing - taxiways and stand connections (no runways)*/
	private Set<TaxiEdge> allTaxiEdges;

	/**the set of all nodes involved in taxiing - taxiways, stands, and runway crossings*/
	private Set<TaxiNode> allTaxiNodes;
	
	/**the graph of the airport also including runways*/
	private WeightedMultigraph<TaxiNode,TaxiEdge> graphWholeAirport;
	
	/**the graph of the airport excluding runways (ie, just the edges suitable for taxiing)*/
	private WeightedMultigraph<TaxiNode,TaxiEdge> graphTaxiways;
	
	/**integer IDs assigned to TaxiNodes for by GroundMovementWriter*/
	private Map<TaxiNode, Integer> gmwIDsForTaxiNodes;

	/**integer IDs assigned to TaxiEdges for by GroundMovementWriter*/
	private Map<TaxiEdge, Integer> gmwIDsForTaxiEdges;
	
	/**
	 * usage: TaxiGen OSMInputFile GMOutputFile [options]
	 * OSMInputFile: the xml extracted from Open Street Map
	 * StandsInputFile: tab separated file; each line has either stand name only (if OSM contains edges for stands), or standName Lat Lon Terminal TaxiwayName [SpecificNodeToAttachTo]
	 * GMOutputFile: filename to write output to
	 * 
	 * options:
	 * -stands=filename.txt : a filename containing details of stands to add to the OSM taxiways
	 * -angles=filename.txt : a filename to write out angles between edges
	 * -kml=filename.kml : a filename to write out KML for the taxiways for use in Google Earth
	 * -spacing==n : number of metres between intermediate nodes on long taxiway edges (default=50); make negative to disable 
	 * -minDistance=n : when adding a node to a taxiway for a stand to attach to, if a node exists within this distance, attach to that instead (default=1)
	 * -rw=y/n : include runways in outputs? y/n (default = y)
	 */
	public static void main(String[] args) {
		Legal.printLicence("TaxiGen");
		if (args.length < 2) {
			printUsage();
			System.exit(0);
		}
		
		String osmDataFile = args[0];
		String outputGMFile = args[1];
		String standsDataFile = null;
		String edgeAnglesFile = null;
		String kmlFile = null;
		boolean includeRunways = true;
		double thresholdForSnapToNode = 1;
		double spacingForIntermediates = 50;
		
		for (int i = 2; i < args.length; i++) {
			String arg = args[i];
			String argLC = arg.toLowerCase();
			if (argLC.startsWith("-stands=")) {
				standsDataFile = arg.substring(8);
			} else if (argLC.startsWith("-angles=")) {
				edgeAnglesFile = arg.substring(8);
			} else if (argLC.startsWith("-kml=")) {
				kmlFile = arg.substring(5);
			} else if (argLC.startsWith("-spacing=")) {
				try {
					spacingForIntermediates = Double.parseDouble(arg.substring(9));
				} catch (NumberFormatException e) {
					System.err.println("Trouble reading -spacing value:" + arg.substring(9));
					System.exit(1);
				}
			} else if (argLC.startsWith("-mindistance=")) {
				try {
					thresholdForSnapToNode = Double.parseDouble(arg.substring(13));
				} catch (NumberFormatException e) {
					System.err.println("Trouble reading -minDistance value:" + arg.substring(13));
					System.exit(1);
				}
			} else if (argLC.startsWith("-rw=")) {
				String s = argLC.substring(4);
				includeRunways = s.contains("y") || s.contains("t");
			} else {
				System.err.println("Unknown parameter: " + arg);
				System.exit(1);
			}
		}

		System.out.println("OSM file: " + osmDataFile);
		System.out.println("GM file: " + outputGMFile);
		if (standsDataFile != null) System.out.println("Stands file: " + standsDataFile);
		if (edgeAnglesFile != null) System.out.println("Edge angles file: " + edgeAnglesFile);
		if (kmlFile != null) System.out.println("KML file: " + kmlFile);
		System.out.println((spacingForIntermediates >= 0) ? "Intermediates spaced at: " + spacingForIntermediates + "m" : "No intermediates");
		System.out.println((thresholdForSnapToNode > 0) ? "Threshold for snap to existing nodes: " + thresholdForSnapToNode + "m" : "Not snapping to existing nodes");
		System.out.println(includeRunways ? "Including runways in output" : "Excluding runways from output");
		System.out.println("Now processing...");
		
		TaxiGen at = new TaxiGen(standsDataFile, osmDataFile, thresholdForSnapToNode, spacingForIntermediates);
		
		System.out.println("Writing GM file:" + outputGMFile);
		GroundMovementWriter gmw = at.graphNodesAndEdgesToGMFile(false);
		gmw.writeFile(outputGMFile);
		
		if (edgeAnglesFile != null) {
			System.out.println("Writing edge angles:" + edgeAnglesFile);
			at.graphEdgeAnglesToGMStyleFile(edgeAnglesFile, !includeRunways);
		}
		
		if (kmlFile != null) {
			System.out.println("Writing KML:" + kmlFile);
			at.graphNodesAndEdgesToKML(kmlFile, !includeRunways);
		}
		
		System.out.println("All done.");
	}
	
	public static void printUsage() {
		System.out.println("Usage: TaxiGen osmInputFile gmOutputFile [options]");
		System.out.println(" osmInputFile: the xml extracted from Open Street Map");
		System.out.println(" gmOutputFile: filename to write output to");
		System.out.println();
		System.out.println("Options:");
		System.out.println(" -stands=filename.txt : filename containing details of stands to add to the OSM taxiways. Tab separated; each line has either stand name only (if OSM contains edges for stands), or standName Lat Lon Terminal TaxiwayName [SpecificNodeToAttachTo]");
		System.out.println(" -angles=filename.txt : filename to write angles between edges");
		System.out.println(" -kml=filename.kml : filename to write KML for taxiways");
		System.out.println(" -spacing=n : spacing in metres between intermediate nodes on long taxiway edges (default=50); negative to disable"); 
		System.out.println(" -minDistance=n : when adding a node to a taxiway for a stand to attach to, if a node exists within this distance, attach to that instead (default=1)");
		System.out.println(" -rw=y/n : include runways in outputs? y/n (default = y)");
		System.out.println();
	}
	
	/** initialise taxiways object - load airport structure from OSM and NATS stand locations*/
	public TaxiGen(String standsDataFile, String osmDataFile, double thresholdForSnapToNode, double spacingForIntermediates) {
		this.thresholdForSnapToNode = thresholdForSnapToNode;
		this.spacingForIntermediates = spacingForIntermediates;
		
		// each taxiway object is a list of edges making up a taxiway; here indexed by taxiway name
		Map<String,Taxiway> taxiways = new TreeMap<String, Taxiway>();
		
		Stands stands = null;
		if (standsDataFile != null) {
			System.out.println("Loading stand data");
			stands = new Stands(standsDataFile);
		}
		Set<String> standsWithNoCoords = (stands != null) ? stands.getStandsWithNoCoords() : null;
		
		// create edges from OSM data
		System.out.println("Loading OSM data");
		
		//the nodes from openstreetmap - covering runways and taxiways; indexed by node OSM ID
		Map<String, TaxiNode> osmNodes = new TreeMap<String,TaxiNode>();
		
		this.allEdges = new TreeSet<TaxiEdge>();
		taxiways.putAll(loadNodesFromOSM(osmDataFile, osmNodes, allEdges, standsWithNoCoords));
		
		// load and create nodes for gates from NATS data
		System.out.println("Adding NATS stands");
		Map<String, TaxiNode> addedStandNodes = (stands != null) ? stands.getStandNodes() : null;
		
		// for each gate node, find all edges related to the taxiway it's connected to, and
		// figure out the nearest point on each. Whichever is nearest overall, add an intersection node
		// (if one doesn't already exist within a radius of a couple of metres) and add an edge to the node
		// (also delete existing edge and add two new ones to include that node in the taxiway)

		// revised: have lots of intermediate nodes, and just look for the nearest one on the right taxiway
		Map<String,TaxiNode> gateAdditionalNodes = new TreeMap<String,TaxiNode>(); // the set of any extra nodes added to the OSM data to allow for stand connections
		if (stands != null) {
			System.out.println("Adding nodes for added stands");
			Set<TaxiNode> standNodesWithSpecificAttachments = new TreeSet<TaxiNode>(); // keep these until the end so we can pick up generated stand attachment nodes too 
			for (TaxiNode tn : addedStandNodes.values()) {
				if (tn.getNodeAttachment() != null) {
					standNodesWithSpecificAttachments.add(tn);
				} else {
					// which taxiways is the node associated with?
					List<Taxiway> associatedTaxiways = new ArrayList<Taxiway>();
					for (String tw : tn.getAssociatedTaxiways()) {
						if (taxiways.containsKey(tw.toUpperCase())) {
							associatedTaxiways.add(taxiways.get(tw.toUpperCase()));
						} else {
							System.out.println("Couldn't find taxiway " + tw + " for node " + tn);
						}
					}
					
					// find nearest node
					findNearestEdgeOnTaxiways(associatedTaxiways, tn, allEdges, gateAdditionalNodes);
				}
			}
			for (TaxiNode tn : standNodesWithSpecificAttachments) {
				// which taxiways is the node associated with?
				List<Taxiway> associatedTaxiways = new ArrayList<Taxiway>();
				for (String tw : tn.getAssociatedTaxiways()) {
					if (taxiways.containsKey(tw.toUpperCase())) {
						associatedTaxiways.add(taxiways.get(tw.toUpperCase()));
					} else {
						System.out.println("Couldn't find taxiway " + tw + " for node " + tn);
					}
				}
				attachNodeToSpecificNode(associatedTaxiways, tn, allEdges, gateAdditionalNodes, osmNodes);
			}
		}
		
		// add intermediate nodes to long edges
		System.out.println("Adding intermediate nodes");
		Set<TaxiNode> intermediateNodes = new TreeSet<TaxiNode>(); // the set of any extra nodes added to the OSM data to break up long edges
		addIntermediateNodesToTaxiwayEdges(taxiways, allEdges, intermediateNodes);
		
		this.runways = locateRunways(allEdges);
		
		// now add those nodes and edges to appropriate stores
		this.graphWholeAirport = new WeightedMultigraph<TaxiNode,TaxiEdge>(TaxiEdge.class);
		this.allNodes = new TreeMap<String, TaxiNode>();
		for (TaxiNode tn : osmNodes.values()) {
			graphWholeAirport.addVertex(tn);
			allNodes.put(tn.getId(), tn);
		}
		
		// stands
		this.standNodes = new TreeMap<String, TaxiNode>();
		// first, get any stands that were in the OSM data
		for (TaxiNode tn : osmNodes.values()) {
			if (tn.getNodeType() == NodeType.STAND) {
				standNodes.put(tn.getMeta(), tn);
			}
		}
		// now for additional stands
		if (addedStandNodes != null) {
			for (Entry<String, TaxiNode> e : addedStandNodes.entrySet()) {
				standNodes.put(e.getKey(), e.getValue());
				graphWholeAirport.addVertex(e.getValue());
				allNodes.put(e.getValue().getId(), e.getValue());
			}
		}
		
		for (TaxiNode tn : gateAdditionalNodes.values()) {
			graphWholeAirport.addVertex(tn);
			allNodes.put(tn.getId(), tn);
		}
		
		for (TaxiNode tn : intermediateNodes) {
			graphWholeAirport.addVertex(tn);
			allNodes.put(tn.getId(), tn);
		}
		
		for (TaxiEdge te : allEdges) {
			graphWholeAirport.addEdge(te.getTnFrom(), te.getTnTo(), te);
			graphWholeAirport.setEdgeWeight(te, te.getLength());
		}
		
		this.graphTaxiways = new WeightedMultigraph<TaxiNode,TaxiEdge>(TaxiEdge.class);
		this.allTaxiEdges = new TreeSet<TaxiEdge>();
		this.allTaxiNodes = new TreeSet<TaxiNode>();
		for (TaxiNode tn : allNodes.values()) {
			if (tn.getNodeType() != TaxiNode.NodeType.RUNWAY) {
				graphTaxiways.addVertex(tn);
				allTaxiNodes.add(tn);
			}
		}
		for (TaxiEdge te : allEdges) {
			if (te.getType() != TaxiEdge.Type.RUNWAY) {
				graphTaxiways.addEdge(te.getTnFrom(), te.getTnTo(), te);
				graphTaxiways.setEdgeWeight(te, te.getLength());
				allTaxiEdges.add(te);
			}
		}
	}
	
	/** initialise taxiways object - load airport structure from GM file*/
	public TaxiGen(GroundMovementWriter gmw) {
		this.allEdges = new TreeSet<TaxiEdge>();
		this.allNodes = new TreeMap<String, TaxiNode>();
		this.standNodes = new TreeMap<String, TaxiNode>();
		this.allTaxiNodes = new TreeSet<TaxiNode>();
		this.allTaxiEdges = new TreeSet<TaxiEdge>();
		this.gmwIDsForTaxiNodes = new TreeMap<TaxiNode,Integer>();
		this.gmwIDsForTaxiEdges = new TreeMap<TaxiEdge,Integer>();
		Map<Integer, TaxiNode> tnsForGMWIDs = new HashMap<Integer, TaxiNode>();
		
		// iterate over all edges and nodes...
		for (GroundMovementWriter.Node n : gmw.getNodes()) {
			String name = n.getName();
			String id = n.getSeqNo() + (((name != null) && !name.isEmpty()) ? "-" + n.getName() : "");
			NodeType type = TaxiNode.gmSpecificationToNodeType(n.getSpecification());
			TaxiNode tn = new TaxiNode(id, type, n.getLatitude(), n.getLongitude());
			if (name != null) {
				tn.setMeta(name);
			}
			
			// put node into the relevant stores
			this.allNodes.put(id, tn);
			if (n.getSpecification() == GroundMovementWriter.Node.Specification.gate) {
				this.standNodes.put(name, tn);
			}
			this.gmwIDsForTaxiNodes.put(tn, n.getSeqNo());
			tnsForGMWIDs.put(n.getSeqNo(), tn);
		}
		
		for (GroundMovementWriter.Edge e : gmw.getEdges()) {
			String id = Integer.toString(e.getSeqNo());
			TaxiNode tnFrom = tnsForGMWIDs.get(e.getStartNode());
			TaxiNode tnTo = tnsForGMWIDs.get(e.getEndNode());
			if ((tnFrom == null) || (tnTo == null)) { System.out.println("Error: couldn't find nodes for edge " + e.getSeqNo()); }
			
			TaxiEdge te = new TaxiEdge(id, new Taxiway("",Taxiway.Type.TAXIWAY), tnFrom, tnTo, e.getLength(), TaxiEdge.gmSpecificationToType(e.getSpecification()));
			
			if ((e.getSpecification()==GroundMovementWriter.Edge.Specification.runway)||(e.getSpecification()==GroundMovementWriter.Edge.Specification.gate)) {
				te.setMeta(e.getName());
				
				// sometimes meta information not stored with edges; if so, then pull it from the nodes instead
				if (te.getMeta().isEmpty()) {
					if (e.getSpecification()==GroundMovementWriter.Edge.Specification.gate) {
						if (te.getTnFrom().getNodeType()==NodeType.STAND) {
							te.setMeta(te.getTnFrom().getMeta());
						} else {
							te.setMeta(te.getTnFrom().getMeta());
						}
					} else { // for runways, we can use either node
						te.setMeta(te.getTnFrom().getMeta());
					}
				}
			}
			
			// put edge into the relevant stores
			this.allEdges.add(te);
			if (e.getSpecification() == GroundMovementWriter.Edge.Specification.taxiway) {
				this.allTaxiEdges.add(te);
				
				// if either of the nodes are runway nodes, the fact that they're touched by this taxiway edge means that they're actually runway crossings
				if (tnFrom.getNodeType() == NodeType.RUNWAY) {
					tnFrom.setNodeType(NodeType.RUNWAY_CROSSING);
				}
				if (tnTo.getNodeType() == NodeType.RUNWAY) {
					tnTo.setNodeType(NodeType.RUNWAY_CROSSING);
				}
			} else if (e.getSpecification() == GroundMovementWriter.Edge.Specification.gate) {
				this.allTaxiEdges.add(te);
			}
			this.gmwIDsForTaxiEdges.put(te, e.getSeqNo());
		}
		
		this.runways = locateRunways(allEdges);
		
		// now add those nodes and edges to appropriate stores
		this.graphWholeAirport = new WeightedMultigraph<TaxiNode,TaxiEdge>(TaxiEdge.class);
		for (TaxiNode tn : allNodes.values()) {
			graphWholeAirport.addVertex(tn);
		}
		
		for (TaxiEdge te : allEdges) {
			graphWholeAirport.addEdge(te.getTnFrom(), te.getTnTo(), te);
			graphWholeAirport.setEdgeWeight(te, te.getLength());
		}
		
		// having figured out which nodes are runway crossings rather than just runways, we can now add all non-runway nodes to allTaxiNodes
		this.graphTaxiways = new WeightedMultigraph<TaxiNode,TaxiEdge>(TaxiEdge.class);
		for (TaxiNode tn : allNodes.values()) {
			if (tn.getNodeType() != TaxiNode.NodeType.RUNWAY) {
				graphTaxiways.addVertex(tn);
				allTaxiNodes.add(tn);
			}
		}
		for (TaxiEdge te : allTaxiEdges) {
			graphTaxiways.addEdge(te.getTnFrom(), te.getTnTo(), te);
			graphTaxiways.setEdgeWeight(te, te.getLength());
		}
	}
	
	public void graphNodesAndEdgesToKML(String filename, boolean excludeRunways) {
		if (excludeRunways) {
			graphNodesAndEdgesToKML(filename, allTaxiNodes, allTaxiEdges);
		} else {
			graphNodesAndEdgesToKML(filename, allNodes.values(), allEdges);
		}
	}
	
	public TaxiEdge getEdgeByGMWId(int id) {
		for (TaxiEdge te : this.allEdges) {
			int thisID = this.gmwIDsForTaxiEdges.get(te);
			if (thisID == id) {
				return te;
			}
		}
		
		return null;
	}
	
	public TaxiNode getNodeByGMWId(int id) {
		for (TaxiNode tn : this.allNodes.values()) {
			int thisID = this.gmwIDsForTaxiNodes.get(tn);
			if (thisID == id) {
				return tn;
			}
		}
		
		return null;
	}
	
	public void graphEdgeAnglesToGMStyleFile(String filename, boolean excludeRunways) {
		try {
			PrintStream out = new PrintStream(new FileOutputStream(filename));
			out.println("#;edge1;edge2;edge1node1;edge1node2;edge2node1;edge2node2;angleInDegrees;");
			
			// get all nodes in graph
			Collection<TaxiNode> nodes = excludeRunways ? this.allTaxiNodes : this.allNodes.values();
			WeightedMultigraph<TaxiNode, TaxiEdge> graph = excludeRunways ? this.graphTaxiways : this.graphWholeAirport;
			
			for (TaxiNode tn : nodes) {
				// get all edges coming out of each node
				Set<TaxiEdge> edges = graph.edgesOf(tn);
				
				// for each pair of edges, calc the angle
				for (TaxiEdge te1 : edges) {
					for (TaxiEdge te2 : edges) {
						if (te1 != te2) {
							double angle = angleBetweenEdges(te1, te2);
							
							// get nodes at opposite ends of the edges
							TaxiNode tn1 = (te1.getTnFrom() == tn) ? te1.getTnTo() : te1.getTnFrom();
							TaxiNode tn2 = (te2.getTnFrom() == tn) ? te2.getTnTo() : te2.getTnFrom();
							
							// get indices of the edges and nodes
							int indexSharedNode = getGMWTaxiNodeID(tn);
							int indexNode1 = getGMWTaxiNodeID(tn1);
							int indexNode2 = getGMWTaxiNodeID(tn2);
							int indexEdge1 = getGMWTaxiEdgeID(te1);
							int indexEdge2 = getGMWTaxiEdgeID(te2);
							
							// write out to file (format is ;edge1;edge2;edge1node1;edge1node2;edge2node1;edge2node2;angleInDegrees;)
							out.println(";" + indexEdge1 + ";" + indexEdge2 + ";" + indexNode1 + ";" + indexSharedNode + ";" + indexSharedNode + ";" + indexNode2 + ";" + angle + ";");
						}
					}
				}
			}
			
			out.close();
		} catch (IOException e) {
			System.err.println("Error writing angles out to " + filename);
			e.printStackTrace();
		}
	}
	
	public GroundMovementWriter graphNodesAndEdgesToGMFile(boolean excludeRunways) {
		gmwIDsForTaxiNodes = new TreeMap<TaxiNode,Integer>();
		gmwIDsForTaxiEdges = new TreeMap<TaxiEdge,Integer>();
		if (excludeRunways) {
			return graphNodesAndEdgesToGMFile(this.allTaxiNodes, this.allTaxiEdges, gmwIDsForTaxiNodes, gmwIDsForTaxiEdges);
		} else {
			return graphNodesAndEdgesToGMFile(this.allNodes.values(), this.allEdges, gmwIDsForTaxiNodes, gmwIDsForTaxiEdges);
		}
	}
	
	private static void attachNodeToSpecificNode(List<Taxiway> taxiways, TaxiNode tnFrom, Set<TaxiEdge> edgeSet, Map<String,TaxiNode> nodeMap, Map<String,TaxiNode> osmNodes) {
		// just add an edge for that and return
		// look for node in osm nodes and nodes added for stands
		TaxiNode toAttachTo = osmNodes.get(tnFrom.getNodeAttachment());
		if (toAttachTo == null) {
			toAttachTo = nodeMap.get(tnFrom.getNodeAttachment());
		}
		if (toAttachTo == null) {
			System.out.println("Couldn't find node to attach to! Stand " + tnFrom + " toAttachTo " + tnFrom.getNodeAttachment());
		} else {
			// an edge to connect to the stand
			TaxiEdge teS = new TaxiEdge("ES" + tnFrom.getId() + "-" + taxiways.get(0).getName(), taxiways.get(0), tnFrom, toAttachTo, distance(tnFrom, toAttachTo), TaxiEdge.Type.STAND_CONNECTION);
			edgeSet.add(teS);
			return;
		}
	}
	
	/**find nearest point on an edge to given node: add a node and an edge for that point and remove the original edge
	 */
	private void findNearestEdgeOnTaxiways(List<Taxiway> taxiways, TaxiNode tnFrom, Set<TaxiEdge> edgeSet, Map<String,TaxiNode> nodeMap) {
		// for each taxiway...
		double[][] nearestCoords = new double[taxiways.size()][2];
		TaxiEdge[] edgesToReplace = new TaxiEdge[taxiways.size()];
		TaxiNode[] nodesToUse = new TaxiNode[taxiways.size()];
		double[] minDistances = new double[taxiways.size()];
		for (int twi = 0; twi < taxiways.size(); twi++) { // loop over each taxiway associated with this stand
			Taxiway tw = taxiways.get(twi);
			
			// otherwise, loop over all edges on the given taxiway, loop over to look for nearest (Euclidean distance)
			minDistances[twi] = Double.POSITIVE_INFINITY;
			for (TaxiEdge te : tw.getEdges()) {
				// get nearest point on the edge
				double[] coords = nearestPointOnLine(tnFrom.getLatCoordinate(), tnFrom.getLonCoordinate(), te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate(), te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate());
				// NB coords are lat,lon
				
				// if the intersection coords are beyond the end of an edge, just use the node at the end of the edge
				// can just check one of the coordinates as it's a straight line
				boolean beyondEndOfEdge = (coords[0] <= Math.min(te.getTnFrom().getLatCoordinate(), te.getTnTo().getLatCoordinate())) || (coords[0] >= Math.max(te.getTnFrom().getLatCoordinate(), te.getTnTo().getLatCoordinate()));
				
				double distance;
				TaxiNode nodeToUse = null;
				if (beyondEndOfEdge) {
					// which node is closest?
					double distance1 = distance(tnFrom, te.getTnFrom());
					double distance2 = distance(tnFrom, te.getTnTo());
					if (distance1 < distance2) {
						nodeToUse = te.getTnFrom();
						distance = distance1;
					} else {
						nodeToUse = te.getTnTo();
						distance = distance2;
					}
				} else {
					// If on an edge, before we commit to checking whether to add a new node (breaking the edge in two), see if the end of the edge is pretty close.
					// If it is, just connect to that.
					double distanceFromPointToNode1 = distance(te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate(), coords[0], coords[1]);
					double distanceFromPointToNode2 = distance(te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate(), coords[0], coords[1]);
					
					if (distanceFromPointToNode1 < thresholdForSnapToNode) {
						distance = distance(tnFrom, te.getTnFrom());
						nodeToUse = te.getTnFrom();
						beyondEndOfEdge = true; // set this so we'll use the node rather than the edge
					} else if (distanceFromPointToNode2 < thresholdForSnapToNode) {
						distance = distance(tnFrom, te.getTnTo());
						nodeToUse = te.getTnTo();
						beyondEndOfEdge = true; // set this so we'll use the node rather than the edge
					} else {
						distance = distance(tnFrom.getLatCoordinate(), tnFrom.getLonCoordinate(), coords[0], coords[1]);
					}
				}
						
				if (distance < minDistances[twi]) {
					minDistances[twi] = distance;
					
					if (beyondEndOfEdge) {
						nodesToUse[twi] = nodeToUse;
						Arrays.fill(nearestCoords[twi], Double.NaN);
						edgesToReplace[twi] = null;
					} else {
						nodesToUse[twi] = null;
						nearestCoords[twi] = coords;
						edgesToReplace[twi] = te;
					}
				}
			}
		}

//		System.out.println(tnFrom + ":" + nodesToUse[0] + "," + edgesToReplace[0]);
		// now, loop through the coords. Add a point for each, insert into taxiway (remove the original edge and add two new edges) and add an edge to the stand 
		for (int i = 0; i < nearestCoords.length; i++) {
			// if we've not already got an explicit node to use
			TaxiNode tn;
			if (nodesToUse[i] == null) {
				// add a node on the taxiway
				tn = new TaxiNode("TS-" +taxiways.get(i).getName()+"-"+ tnFrom.getId(), NodeType.INTERSECTION, nearestCoords[i][0], nearestCoords[i][1]);
				nodeMap.put(tn.getId(), tn);
			} else { // instead, use the already-chosen node
				tn = nodesToUse[i];
			}
			
			// an edge to connect to the stand
			TaxiEdge teS = new TaxiEdge("ES" + tnFrom.getId() + "-" + taxiways.get(i).getName(), taxiways.get(i), tnFrom, tn, minDistances[i], TaxiEdge.Type.STAND_CONNECTION);
			edgeSet.add(teS);
			
			// two new edges to join to the new edge, if we're meant to be replacing the edge
			if (edgesToReplace[i] != null) {
				TaxiEdge te1 = new TaxiEdge(edgesToReplace[i].getId(), taxiways.get(i), edgesToReplace[i].getTnFrom(), tn, distance(edgesToReplace[i].getTnFrom(), tn), TaxiEdge.Type.TAXIWAY);
				TaxiEdge te2 = new TaxiEdge(edgesToReplace[i].getId(), taxiways.get(i), tn, edgesToReplace[i].getTnTo(), distance(tn, edgesToReplace[i].getTnTo()), TaxiEdge.Type.TAXIWAY);
	
				edgeSet.remove(edgesToReplace[i]);
				edgeSet.add(te1);
				edgeSet.add(te2);
			
				// also amend taxiway object
				taxiways.get(i).replaceEdge(edgesToReplace[i], Arrays.asList(new TaxiEdge[]{te1, te2}));
			}
		}
	}
	
	/**
	 * currently unused. May be useful in the future.
	 * get coords of runways. each is just defined with a key (RW name) and a 2D array holding two pairs of lat+lon coords marking the ends
	 */
	public static Map<String, double[][]> loadRunwayNodesFromOSM(String filename) {
		ParseOSM posm = new ParseOSM(filename);
		Map<Long, List<AeroWay>> m = posm.getWayNodes();
		
		// first, get all the nodes for each runway
		Map<String, List<double[]>> coords = new TreeMap<String, List<double[]>>();
		for (Entry<Long, List<AeroWay>> e : m.entrySet()) {
			for (AeroWay w : e.getValue()) {
				if (w.type == Type.RUNWAY) {
					Node n = posm.getNode(e.getKey());
					
					List<double[]> coordsForThisRunway = coords.get(w.name);
					if (coordsForThisRunway == null) {
						coordsForThisRunway = new ArrayList<double[]>();
						coords.put(w.name, coordsForThisRunway);
					}
					
					coordsForThisRunway.add(new double[] {n.getLatitude(), n.getLongitude()});
				}
			}
		}
		
		// now get ends of each runway
		Map<String, double[][]> rval = new TreeMap<String, double[][]>();
		for (Entry<String, List<double[]>> e : coords.entrySet()) {
			double minLat = Double.POSITIVE_INFINITY;
			double minLatsLon = 0;
			double maxLat = Double.NEGATIVE_INFINITY;
			double maxLatsLon = 0;
			
			for (double[] d : e.getValue()) {
				if (d[0] < minLat) {
					minLat = d[0];
					minLatsLon = d[1];
				} else if (d[0] > maxLat) {
					maxLat = d[0];
					maxLatsLon = d[1];
				}
			}
			
			rval.put(e.getKey(), new double[][] {{minLat,minLatsLon},{maxLat,maxLatsLon}});
		}
		
		return rval; 
	}
	
	private static Map<String, Taxiway> loadNodesFromOSM(String filename, Map<String, TaxiNode> nodeStore, Set<TaxiEdge> edgeStore, Set<String> specifiedStandNames) {
		// somewhere to keep all the edges and nodes for each taxiway
		Map<String, Taxiway> taxiways = new TreeMap<String, Taxiway>();
		
		if (specifiedStandNames == null) {
			specifiedStandNames = new TreeSet<String>(); // for cleaner code later
		}
		
		// load all edges ("ways") first then load nodes
		ParseOSM posm = new ParseOSM(filename);
		
		// need to figure out whether a node is in a runway, a taxiway, or both, and whether it's one or more of each
		// this will determine the node type as well as the edges that connect to it.
		Map<Long, List<AeroWay>> m = posm.getWayNodes();
		Long[] keys = m.keySet().toArray(new Long[m.size()]);
		Arrays.sort(keys); // always get in the same order
		
		// entries map node IDs to associated Way objects
		for (Long key : keys) {
			// loop over all the ways that the node is associated with, and count how many of each type there are
			int numRunways = 0;
			int numTaxiways = 0;
			int numStands = 0;
			boolean endOfStandWay = false; // if this node is at the end of a "stand" way (so this node is the stand itself)
			
			String runwayNames = "";
			String standName = "";
			List<AeroWay> a = m.get(key);
			for (int i = 0; i < a.size(); i++) {
				AeroWay w = a.get(i);
				if (w.type == Type.TAXIWAY) {
					numTaxiways++;
				} else if (w.type == Type.RUNWAY) {
					runwayNames += ((numRunways==0)?"":",")+w.name;
					numRunways++;
				} else if ((w.type == Type.STAND) || (specifiedStandNames.contains(w.name))) {
					standName = w.name;
					numStands++;
					
					if (key == w.way.getWayNodes().get(w.way.getWayNodes().size() - 1).getNodeId()) { // that is, if this node is found at the end of the stand way
						endOfStandWay = true;
					}
				}
			}
			
			TaxiNode.NodeType type = null; // stand type is allocated elsewhere as we don't get that from OSM ()
			if ((numRunways > 0) && (numTaxiways > 0)) {
				type = NodeType.RUNWAY_CROSSING;
			} else if (endOfStandWay && (numStands > 0) && (numTaxiways == 0)) { // stand node is only the one at the end (not on the taxiway)
				type = NodeType.STAND;
			} else if (((numRunways == 0) && (numTaxiways == 1)) || (!endOfStandWay && (numStands > 0) && (numTaxiways == 0))) { // intermediate is either on a midpoint of a taxiway, or on a midpoint of a stand
				type = NodeType.INTERMEDIATE;
			} else if ((numRunways == 0) && (numTaxiways > 1)) {
				type = NodeType.INTERSECTION;
			} else if ((numRunways > 0) && (numTaxiways == 0)) {
				type = NodeType.RUNWAY;
			}
			
			if (type != null) {
				Node n = posm.getNode(key);
				// -1 * y coord, because lat is up as values increase, whereas screen is down as values increase
				TaxiNode tn = new TaxiNode("N" + n.getId(), type, n.getLatitude(), n.getLongitude());
				nodeStore.put(tn.getId(), tn);

				// add names of runways associated with this node
				if ((type == NodeType.RUNWAY) || (type == NodeType.RUNWAY_CROSSING)) {
					String rw = tn.getMeta();
					rw += (rw.isEmpty()?"":",")+runwayNames;
					tn.setMeta(rw);
				}
				
				if (type == NodeType.STAND) {
					tn.setMeta(standName);
				}
			}
		}
				
		// now iterate over all the taxiways, and add edges to the graph as appropriate
		for (AeroWay w : posm.getWays()) {
			if ((w.type == Type.TAXIWAY) || (w.type == Type.RUNWAY) || (w.type == Type.STAND)) {
				boolean stand = (w.type == Type.STAND) || specifiedStandNames.contains(w.name);
				Taxiway tw = null;
				
				if (!stand) {
					tw = taxiways.get(w.name);

					if (tw == null) {
						tw = new Taxiway(w.name, w.type==Type.TAXIWAY ? Taxiway.Type.TAXIWAY : Taxiway.Type.RUNWAY);
						taxiways.put(w.name, tw);
					}
				}
				
				// get nodelist for this Way, and add an edge for each pair of nodes
				List<WayNode> l = w.way.getWayNodes();
				for (int i = 0; i < l.size() - 1; i++) {
					TaxiNode tnFrom = nodeStore.get("N" + l.get(i).getNodeId());
					TaxiNode tnTo = nodeStore.get("N" + l.get(i + 1).getNodeId()); // for info only: for stands/parking_positions, the latter node will always be the stand node (NB - sometimes stands are curved, so there are >1 edges in them; only really interested in the last one) 
					
					if (tnFrom != null && tnTo != null) { // if either is null, that's a node we didn't want to include (maybe it was runway only), so don't bother adding an adge to it
						double length = distance(tnFrom, tnTo);
						
						TaxiEdge te = new TaxiEdge("E" + w.way.getId(), tw, tnFrom, tnTo, length, (stand ? TaxiEdge.Type.STAND_CONNECTION : (w.type==Type.TAXIWAY ? TaxiEdge.Type.TAXIWAY : TaxiEdge.Type.RUNWAY)));
						
						if (te.getType() == TaxiEdge.Type.RUNWAY) {
							te.setMeta(w.name);
						}
						
						if (stand) {
							te.setMeta(w.name);
						}
						
						edgeStore.add(te);
						
						if (tw != null) {
							tw.addEdge(te);
							tw.addNode(tnFrom);
							if (i == l.size() - 1) {
								tw.addNode(tnTo);
							}
						}
					}
				}
			}
		}
		
		return taxiways;
	}
	
	/**
	 * Only adds to taxiways, not runways or stand connections
	 * @param taxiways the map of taxiways to add intermediate nodes to
	 * @param allEdges the set of edges which will be modified (most will be removed and replaced with smaller ones)
	 * @param nodes a set into which newly created nodes will be inserted
	 */
	private void addIntermediateNodesToTaxiwayEdges(Map<String, Taxiway> taxiways, Set<TaxiEdge> allEdges, Set<TaxiNode> nodes) {
		for (Taxiway tw : taxiways.values()) {
			// first, take a look at each edge on the taxiway, and work out which ones need broken up.
			// adding a node for each break
			Map<TaxiEdge,List<TaxiEdge>> toReplaceWith = new TreeMap<TaxiEdge,List<TaxiEdge>>();
			
			for (TaxiEdge te : tw.getEdges()) {
				if (te.shouldHaveIntermediatesAdded()) {
					double length = te.getLength();
					int newNumberOfEdges = (int)(length / spacingForIntermediates);
					if (newNumberOfEdges > 1) {
						// get equation of line for edge
						double mLine = (te.getTnTo().getLonCoordinate() - te.getTnFrom().getLonCoordinate()) / (te.getTnTo().getLatCoordinate() - te.getTnFrom().getLatCoordinate());
				    	double cLine = te.getTnFrom().getLonCoordinate() - (mLine * te.getTnFrom().getLatCoordinate());

				    	// now work from the x coordinate at one end of the edge to the x coord at the other end, creating new nodes along the way
				    	TaxiNode[] intermediateNodes = new TaxiNode[newNumberOfEdges - 1];
				    	double increment = (te.getTnTo().getLatCoordinate() - te.getTnFrom().getLatCoordinate()) / newNumberOfEdges;
				    	double currentX = te.getTnFrom().getLatCoordinate() + increment;
			    		List<TaxiEdge> toAdd = new ArrayList<TaxiEdge>(intermediateNodes.length + 2);
				    	for (int i = 0; i < intermediateNodes.length; i++) {
				    		intermediateNodes[i] = new TaxiNode("TNI-" + te.getUniqueString() + "-" + i, NodeType.INTERMEDIATE, currentX, (mLine * currentX + cLine));
				    		nodes.add(intermediateNodes[i]);

				    		// create edges for the intermediate nodes
				    		if (i == 0) {
				    			toAdd.add(new TaxiEdge(te.getId(), tw, te.getTnFrom(), intermediateNodes[i], distance(te.getTnFrom(), intermediateNodes[i]), TaxiEdge.Type.TAXIWAY));
				    		} else {
				    			toAdd.add(new TaxiEdge(te.getId(), tw, intermediateNodes[i - 1], intermediateNodes[i], distance(intermediateNodes[i - 1], intermediateNodes[i]), TaxiEdge.Type.TAXIWAY));
				    		}
				    		
				    		// add an edge for the last node too
				    		if (i == intermediateNodes.length - 1) {
			    				toAdd.add(new TaxiEdge(te.getId(), tw, intermediateNodes[i], te.getTnTo(), distance(intermediateNodes[i], te.getTnTo()), TaxiEdge.Type.TAXIWAY));
			    			}
				    		
				    		currentX += increment;
				    	}
				    	
			    		// we'll need to remove the original edge
			    		toReplaceWith.put(te, toAdd);
					}
				}
			} // end of loop over edges in taxiway
			
			for (Entry<TaxiEdge, List<TaxiEdge>> e : toReplaceWith.entrySet()) {
				tw.replaceEdge(e.getKey(), e.getValue());
				allEdges.remove(e.getKey());
				allEdges.addAll(e.getValue());
			}
		} // end of loop over taxiways
	}
	
	/**
	 * @return distance in metres
	 */
	public static double distance(TaxiNode tnFrom, TaxiNode tnTo) {
		return distance(tnFrom.getLatCoordinate(), tnFrom.getLonCoordinate(), tnTo.getLatCoordinate(), tnTo.getLonCoordinate());
	}

	/**
	 * @return distance in metres
	 */
	public static double distance(double lat1, double lon1, double lat2, double lon2) {
		LatLng llFrom = new LatLng(lat1, lon1);
		LatLng llTo = new LatLng(lat2, lon2);
		
		double d = distance(llFrom, llTo);
		
		return d;
	}
	
	/**
	 * @return distance in metres
	 */
	public static double distance(LatLng ll1, LatLng ll2) {
		double d = ll1.distance(ll2) * 1000;
		if (d < DISTANCE_THRESHOLD_FOR_ZERO_M) {
			d = 0;
		}
		
		return d;
	}
	
	/**@return double[]{lat,lon}  -- equiv to x,y - assumes line extends beyond the ends that are specified*/
    public static double[] nearestPointOnLine(double pointLat, double pointLon, double endLine1Lat, double endLine1Lon, double endLine2Lat, double endLine2Lon) {
    	// get eqn of line, get eqn of perpendicular line from it to the point, find intersection
    	
    	// first convert to UTM coords so we can do linear maths in metres rather than degrees
    	// this is slow but it's the only simple way to use GPS coords and still be able to plot perpenticular lines
		LatLng llPoint = new LatLng(pointLat, pointLon);
		LatLng llEndLine1 = new LatLng(endLine1Lat, endLine1Lon);
		LatLng llEndLine2 = new LatLng(endLine2Lat, endLine2Lon);
    	UTMRef utmPoint = llPoint.toUTMRef();
    	UTMRef utmEndLine1 = llEndLine1.toUTMRef();
    	UTMRef utmEndLine2 = llEndLine2.toUTMRef();
		double pointX = utmPoint.getEasting();
		double pointY = utmPoint.getNorthing();
		double endLine1X = utmEndLine1.getEasting();
		double endLine1Y = utmEndLine1.getNorthing();
		double endLine2X = utmEndLine2.getEasting();
		double endLine2Y = utmEndLine2.getNorthing();
    	
    	// calc eqn of line
    	double mLine = (endLine2Y - endLine1Y) / (endLine2X - endLine1X);
    	double cLine = endLine1Y - (mLine * endLine1X);
    	
    	// eqn of line to point
    	double mLine2 = -1 / mLine; // perpendicular lines have m1m2=-1, so new gradient is -1/m1
    	double cLine2 = pointY - (mLine2 * pointX);
    	
    	// intersection X (x=c1-c2 / m2-m1)
    	double intX = (cLine - cLine2) / (mLine2 - mLine);
    	double intY = (mLine2 * intX) + cLine2;
    	
    	// convert back to lat/lon
    	UTMRef rval = new UTMRef(intX, intY, utmPoint.getLatZone(), utmPoint.getLngZone());
    	LatLng llRval = rval.toLatLng();
    	
    	return new double[] {llRval.getLat(), llRval.getLng()};
    }
	
    private static void graphNodesAndEdgesToKML(String filename, Collection<TaxiNode> nodes, Collection<TaxiEdge> edges) {
		final Kml kml = KmlFactory.createKml();
		final Document document = kml.createAndSetDocument().withName(filename).withOpen(true);
		final Document documentTaxiways = document.createAndAddDocument().withName(filename + "Taxiways").withOpen(true);
		final Document documentNodes = document.createAndAddDocument().withName(filename + "Nodes").withOpen(true);
		final Style styleOSM = documentNodes.createAndAddStyle().withId("placemarkStyleGates");
		styleOSM.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor("ffff7777").withScale(1);
		
		final Style styleTaxiway = documentTaxiways.createAndAddStyle().withId("linestyleTaxiway");
		styleTaxiway.createAndSetLineStyle()
		.withColor("550000ff")
		.withWidth(4.0d);

		final Style styleRunway = documentTaxiways.createAndAddStyle().withId("linestyleRunway");
		styleRunway.createAndSetLineStyle()
		.withColor("5500ff00")
		.withWidth(4.0d);
		
		final Style styleTaxiwayToGate = documentTaxiways.createAndAddStyle().withId("linestyleTaxiwayToGate");
		styleTaxiwayToGate.createAndSetLineStyle()
		.withColor("55ff0000")
		.withWidth(4.0d);

		for (TaxiEdge te : edges) {
			String style = "#linestyle";
			if (te.getType() == TaxiEdge.Type.TAXIWAY) {
				style += "Taxiway";
			} else if (te.getType() == TaxiEdge.Type.STAND_CONNECTION) {
				style += "TaxiwayToGate";
			} else {
				style += "Runway";
			}
			
			LineString ls = documentTaxiways.createAndAddPlacemark().withName(te.getUniqueString() + "["+te.getTnFrom().getId()+">>>"+te.getTnTo().getId()+"]" + bearing(te)).withStyleUrl(style)
			.createAndSetLineString(); //.withExtrude(true); //.withTessellate(true);

			ls.addToCoordinates(te.getTnFrom().getLonCoordinate() + "," + te.getTnFrom().getLatCoordinate() + ",0");
			ls.addToCoordinates(te.getTnTo().getLonCoordinate() + "," + te.getTnTo().getLatCoordinate() + ",0");
		}
		
		for (TaxiNode tn : nodes) {
			String meta = (tn.getMeta() != null) && (!tn.getMeta().isEmpty()) ? " (" + tn.getMeta() + ")" : "";
			Placemark p = documentNodes.createAndAddPlacemark().withName(tn.getId() + meta).withStyleUrl("#placemarkStyleGates");
			p.createAndSetPoint().addToCoordinates(tn.getLonCoordinate(), tn.getLatCoordinate());
		}
		
		try {
			kml.marshal(new File(filename));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
   
	/**creates object that can write out a notts-formatted ground movement file - doesn't actually write it out in case we want to add to it*/
	private static GroundMovementWriter graphNodesAndEdgesToGMFile(Collection<TaxiNode> nodes, Collection<TaxiEdge> edges, Map<TaxiNode,Integer> nodeIDs, Map<TaxiEdge,Integer> edgeIDs) {
		GroundMovementWriter gmw = new GroundMovementWriter();
		gmw.setSeparationDistanceOnGround(60); // fixed value for now

		for (TaxiNode tn : nodes) {
			GroundMovementWriter.Node.Specification spec = null;
			if (tn.getNodeType() == NodeType.STAND) {
				spec = GroundMovementWriter.Node.Specification.gate;
			} else if (tn.getNodeType() == NodeType.INTERMEDIATE || tn.getNodeType() == NodeType.INTERSECTION) {
				spec = GroundMovementWriter.Node.Specification.intermediate;
			} else if (tn.getNodeType() == NodeType.RUNWAY_CROSSING) {
				spec = GroundMovementWriter.Node.Specification.runway;
			} else if (tn.getNodeType() == NodeType.RUNWAY) {
				spec = GroundMovementWriter.Node.Specification.runway;
			}
			LatLng ll = new LatLng(tn.getLatCoordinate(), tn.getLonCoordinate());
			UTMRef utm = ll.toUTMRef();
			GroundMovementWriter.Node n = new GroundMovementWriter.Node(tn.getMeta(), utm.getEasting(), utm.getNorthing(), tn.getLatCoordinate(), tn.getLonCoordinate(), spec);
			gmw.addNode(n);
			nodeIDs.put(tn, n.getSeqNo());
		}
		
		for (TaxiEdge te : edges) {
			int startNode = nodeIDs.get(te.getTnFrom());
			int endNode = nodeIDs.get(te.getTnTo());
			GroundMovementWriter.Edge.Specification spec;
			if (te.getType() == TaxiEdge.Type.RUNWAY) {
				spec = GroundMovementWriter.Edge.Specification.runway;
			} else if (te.getType() == TaxiEdge.Type.STAND_CONNECTION) {
				spec = GroundMovementWriter.Edge.Specification.gate;
			} else { // assume a taxiway
				spec = GroundMovementWriter.Edge.Specification.taxiway;
			}
			
			GroundMovementWriter.Edge e = new GroundMovementWriter.Edge(startNode, endNode, false, te.getLength(), te.getTraversalTimes(), spec, te.getMeta());
			gmw.addEdge(e);
			edgeIDs.put(te, e.getSeqNo());
		}
				
		return gmw;
	}

	
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

	/**
	 * Takes three nodes - previous, current, and next - and gives the turning angle required at the current node to go from prev to next
	 */
	public static double angleBetweenNodes(TaxiNode prev, TaxiNode current, TaxiNode next) {
		// convert to utm coords to allow 2D maths
		LatLng llprev = new LatLng(prev.getLatCoordinate(), prev.getLonCoordinate());
		LatLng llnext = new LatLng(next.getLatCoordinate(), next.getLonCoordinate());
		LatLng llcurrent = new LatLng(current.getLatCoordinate(), current.getLonCoordinate());
    	
		return Maths.roundDouble(angleBetweenPoints(llprev, llcurrent, llnext), 2);
	}
	
	public static double angleBetweenPoints(LatLng llprev, LatLng llcurrent, LatLng llnext) {
		// convert to utm coords to allow 2D maths
		UTMRef utmPrev = llprev.toUTMRef();
    	UTMRef utmCurrent = llcurrent.toUTMRef();
    	UTMRef utmNext = llnext.toUTMRef();
		double prevX = utmPrev.getEasting();
		double prevY = utmPrev.getNorthing();
		double currentX = utmCurrent.getEasting();
		double currentY = utmCurrent.getNorthing();
		double nextX = utmNext.getEasting();
		double nextY = utmNext.getNorthing();

		double angle1 = Math.atan2(currentY - prevY, currentX - prevX);
		double angle2 = Math.atan2(nextY - currentY, nextX - currentX);
		
		double diff = angle1 - angle2;
		double deg = Math.abs(Math.toDegrees(diff));
		if (deg > 180) {
			deg = 360 - deg;
		}
		
		return deg;
	}
	
	/**gets bearing for an edge (angle from north) when travelling from->to*/
	public static double bearing(TaxiEdge edge) {
		double lon1 = edge.getTnFrom().getLonCoordinate();
		double lon2 = edge.getTnTo().getLonCoordinate();
		double lat1 = Math.toRadians(edge.getTnFrom().getLatCoordinate());
		double lat2 = Math.toRadians(edge.getTnTo().getLatCoordinate());
		double lonDiff = Math.toRadians(lon2 - lon1);
		double y = Math.sin(lonDiff) * Math.cos(lat2);
		double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lonDiff);
		
		return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
	}
	
	/**
	 * @return NaN if the two edges are not connected
	 */
	public static double angleBetweenEdges(TaxiEdge te1, TaxiEdge te2) {
		TaxiNode start;
		TaxiNode end;
		TaxiNode shared;
		if (te1.getTnFrom() == te2.getTnFrom()) {
			shared = te1.getTnFrom();
			start = te1.getTnTo();
			end = te2.getTnTo();
		} else if (te1.getTnFrom() == te2.getTnTo()) {
			shared = te1.getTnFrom();
			start = te1.getTnTo();
			end = te2.getTnFrom();
		} else if (te1.getTnTo() == te2.getTnFrom()) {
			shared = te1.getTnTo();
			start = te1.getTnFrom();
			end = te2.getTnTo();
		} else if (te1.getTnTo() == te2.getTnTo()) {
			shared = te1.getTnTo();
			start = te1.getTnFrom();
			end = te2.getTnFrom();
		} else { // no shared node
			return Double.NaN;
		}
		
		return angleBetweenNodes(start, shared, end);
	}
	
	private static Map<String, Runway> locateRunways(Collection<TaxiEdge> edges) {
		Map<String, Runway> runways = new TreeMap<String, Runway>();
		
		for (TaxiEdge te : edges) {
			if (te.getType() == TaxiEdge.Type.RUNWAY) {
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
	
	public TaxiNode getTaxiNode(String id) {
		return this.allNodes.get(id);
	}
	
	public Map<String, Runway> getRunways() {
		return runways;
	}
	
	public TaxiNode getStand(String standID) {
		return this.standNodes.get(standID);
	}
	
	/**gets the first entrance or last exit for the specified runway (NB - looking for "05" not "05/23")
	 * @param entrance - gets entrance if true, otherwise we get the exit (which is actually the entrance of the runway in the opposing direction)
	 * */
	public TaxiNode getEndRunwayAccess(String runwayID, boolean entrance) {
		// figure out the name of the runway we want
		Runway runway = null;
		for (Runway rw : this.runways.values()) {
			for (String s : rw.getName().split("/")) { // do it this way so that 05 != 05L
				if (s.trim().equalsIgnoreCase(runwayID)) {
					runway = rw;
				}
			}
		}
		
		// is it the lower number in the runway name?
		boolean firstName = runwayID.equalsIgnoreCase(runway.getName1());
		
		// yes - use entranceNodes1, no - use entranceNodes2
		if (firstName) {
			if (entrance) {
				return runway.getEntranceNodes1().get(0);
			} else {
				return runway.getEntranceNodes2().get(0);
			}
		} else {
			if (entrance) {
				return runway.getEntranceNodes2().get(0);
			} else {
				return runway.getEntranceNodes1().get(0);
			}
		}
	}
	
	/**@return a map of TaxiNodes to 2-element String arrays, in which first element is runway name if node was a runway exit point (arrival), and second element is runway name if node used as a runway entrance point (departure)*/
	public Map<TaxiNode, String[]> determineRunwaysForNodes() {
		Map<TaxiNode, String[]> rval = new TreeMap<TaxiNode, String[]>();
		
		for (Runway r : getRunways().values()) {
			for (TaxiNode tn : r.getEntranceNodes1()) {
				rval.put(tn, new String[] {r.getName2(),r.getName1()});
			}
			for (TaxiNode tn : r.getEntranceNodes2()) {
				rval.put(tn, new String[] {r.getName1(),r.getName2()});
			}
		}
		
		return rval;
	}
	
	/**@return -1 if not found*/
	public int getGMWTaxiNodeID(TaxiNode tn) {
		Integer i = gmwIDsForTaxiNodes.get(tn);
		return (i != null) ? i.intValue() : -1;
	}
	
	/**@return -1 if not found*/
	public int getGMWTaxiEdgeID(TaxiEdge te) {
		Integer i = gmwIDsForTaxiEdges.get(te);
		return (i != null) ? i.intValue() : -1;
	}
	
	public WeightedMultigraph<TaxiNode, TaxiEdge> getGraphWholeAirport() {
		return graphWholeAirport;
	}
	
	public Map<String, TaxiNode> getAllNodes() {
		return allNodes;
	}
	
	public Set<TaxiEdge> getAllEdges() {
		return allEdges;
	}
	
	public static class Runway {
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
					double tnFromDistance1 = distance(te.getTnFrom(), end1);
					double tnFromDistance2 = distance(te.getTnFrom(), end2);
					
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
					double tnToDistance1 = distance(te.getTnTo(), end1);
					double tnToDistance2 = distance(te.getTnTo(), end2);
					
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
	
	private static class Stands {
		private Map<String, TaxiNode> standNodes = new TreeMap<String, TaxiNode>();
		private Set<String> standsWithNoCoords;
		
		public Stands(String filename) {
			this.standNodes = new TreeMap<String, TaxiNode>();
			this.standsWithNoCoords = new TreeSet<String>();

			for (Stand s : SpecifiedGateLocations.loadStandsFromFile(filename)) {
				if (s.hasCoords()) {
					TaxiNode tn = new TaxiNode("S-" + s.getName(), NodeType.STAND, s.getLat(), s.getLon());
					tn.setAssociatedTaxiways(s.getAssociatedTaxiways());
					tn.setMeta(s.getName());
					tn.setNodeAttachment(s.getNodeAttachment());
					standNodes.put(s.getName(), tn);
				} else {
					standsWithNoCoords.add(s.getName());
				}
			}
		}
		
		private Map<String, TaxiNode> getStandNodes() {
			return standNodes;
		}
		
		public Set<String> getStandsWithNoCoords() {
			return standsWithNoCoords;
		}
	}
}