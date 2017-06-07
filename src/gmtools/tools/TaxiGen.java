package gmtools.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jgrapht.alg.ConnectivityInspector;
import org.jgrapht.graph.WeightedMultigraph;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;

import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Icon;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.KmlFactory;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Style;
import gmtools.common.Geography;
import gmtools.common.GroundMovementWriter;
import gmtools.common.KMLUtils;
import gmtools.common.Legal;
import gmtools.common.Maths;
import gmtools.graph.GraphManipulation;
import gmtools.graph.Runway;
import gmtools.graph.Stands;
import gmtools.graph.TaxiEdge;
import gmtools.graph.TaxiNode;
import gmtools.graph.TaxiNode.NodeType;
import gmtools.graph.Taxiway;
import gmtools.parsers.ParseBGLXML;
import gmtools.parsers.ParseOSM;
import gmtools.parsers.ParseOSM.AeroWay;
import gmtools.parsers.ParseOSM.AeroWay.Type;
import gmtools.parsers.SpecifiedGateLocations.Stand;
import uk.me.jstott.jcoord.LatLng;
import uk.me.jstott.jcoord.UTMRef;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 *
 * <br/><br/>
 * This class will take OSM data and specified gate locations and generate a graph representation that is about right
 */
public class TaxiGen {
	private static final String BASECOLOUR_FOR_PATHS = "baseColourForPaths";
	private static final String BASECOLOUR_FOR_STANDS = "baseColourForStands";
	private static final String BASECOLOUR_FOR_TAXIWAYS = "baseColourForTaxiways";
	private static final String BASECOLOUR_FOR_RUNWAYS = "baseColourForRunways";
	private static final String BASECOLOUR_FOR_NODES = "baseColourForNodes";
	private static final String BASECOLOUR_FOR_STANDNODES = "baseColourForStandNodes";
	private static final String BACKGROUND_COLOUR = "backgroundColour";
	
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
	 * GMOutputFile: filename to write output to
	 * 
	 * options:
	 * -stands=filename.txt : a filename containing details of stands to add to the OSM taxiways; tab separated file; each line has either stand name only (if OSM contains edges for stands), or standName Lat Lon Terminal TaxiwayName [SpecificNodeToAttachTo]
	 * -angles=filename.txt : a filename to write out angles between edges
	 * -kml=filename.kml : a filename to write out KML for the taxiways for use in Google Earth
	 * -spacing==n : number of metres between intermediate nodes on long taxiway edges (default=50); make negative to disable 
	 * -minDistance=n : when adding a node to a taxiway for a stand to attach to, if a node exists within this distance, attach to that instead (default=1)
	 * -nearest=y/n : if no 'nearest taxiway' is specified for a stand, add it to the nearest taxiway (default = n)
	 * -rw=y/n : include runways in outputs? y/n (default = y)
	 * -gn=y/n : add OSM gate nodes as stands, either connecting to the nearest taxiway, or the one specified in the "stands" text file (default = n)
	 * -bglxml=filename : if supplied, this will read stands from the specified bglxml file (ultimate goal will be to allow full parsing as alternative to OSM)
	 * -cp=oobbggrr : colour used for aircraft paths (hex values for opacity, blue, green and red) default is 2255ee00
	 * -cs=oobbggrr : colour used for stand edges (hex values for opacity, blue, green and red) default is 2255ee00
	 * -ct=oobbggrr : colour used for taxiway edges (hex values for opacity, blue, green and red) default is 2255ee00
	 * -cr=oobbggrr : colour used for runway edges (hex values for opacity, blue, green and red) default is 2255ee00
	 * -cn=oobbggrr : colour used for nodes (hex values for opacity, blue, green and red) default is 2255ee00
	 * -csn=oobbggrr : colour used for stand nodes (hex values for opacity, blue, green and red) default is 2255ee00
	 * -cbg=oobbggrr : colour used for blank background (hex values for opacity, blue, green and red) default is 2255ee00
	 */
	public static void main(String[] args) {
		//args = "export/MAN_OSMStands-with23LStartAsTaxiways.osm export/MAN_OSM_GM.txt -stands=export/MANStands_osm.txt -kml=export/MAN_OSM.kml -angles=export/MAN_OSM_Angles.txt".split("\\s+");
		//args = "i:/Data/BHX/BHX.osm BHX_GM.txt -stands=i:/Data/BHX/1311130531_BHXGateLocations.txt -kml=BHX.kml -angles=BHX_Angles.txt".split("\\s+");
		
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
		boolean addGateNodesAsStands = false;
		boolean addToNearestTaxiway = false;
		double thresholdForSnapToNode = 1;
		double spacingForIntermediates = 50;
		boolean checkConnectivity = true;
		String bglxmlDataFile = null;
		Map<String,String> colours = new HashMap<String,String>();
		colours.put(BASECOLOUR_FOR_PATHS, "22000000"); // opacity then bgr
		colours.put(BASECOLOUR_FOR_STANDS, "55ff0000"); // opacity then bgr
		colours.put(BASECOLOUR_FOR_TAXIWAYS, "550000ff"); // opacity then bgr
		colours.put(BASECOLOUR_FOR_RUNWAYS, "5500ff00"); // opacity then bgr
		colours.put(BASECOLOUR_FOR_NODES, "ff00ffff"); // opacity then bgr
		colours.put(BASECOLOUR_FOR_STANDNODES, "ffff0000"); // opacity then bgr
		colours.put(BACKGROUND_COLOUR, "ffffffff"); // opacity then bgr
		
		for (int i = 2; i < args.length; i++) {
			String arg = args[i];
			String argLC = arg.toLowerCase();
			if (argLC.startsWith("-stands=")) {
				standsDataFile = arg.substring(8);
			} else if (argLC.startsWith("-angles=")) {
				edgeAnglesFile = arg.substring(8);
			} else if (argLC.startsWith("-kml=")) {
				kmlFile = arg.substring(5);
			} else if (argLC.startsWith("-bglxml=")) {
				bglxmlDataFile = arg.substring(8);
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
			} else if (argLC.startsWith("-gn=")) {
				String s = argLC.substring(4);
				addGateNodesAsStands = s.contains("y") || s.contains("t");
			} else if (argLC.startsWith("-nearest=")) {
				String s = argLC.substring(9);
				addToNearestTaxiway = s.contains("y") || s.contains("t");
			} else if (argLC.startsWith("-conn=")) {
				String s = argLC.substring(6);
				checkConnectivity = s.contains("y") || s.contains("t");
			} else if (argLC.matches("^-c..?=")) {
				String hex = argLC.substring(argLC.indexOf('='));
				if ((hex.length() == 8) && (hex.matches("[0-9a-f]+"))) {
					if (argLC.startsWith("-cp=")) {
						colours.put(BASECOLOUR_FOR_PATHS, hex);
					} else if (argLC.startsWith("-cs=")) {
						colours.put(BASECOLOUR_FOR_STANDS, hex);
					} else if (argLC.startsWith("-ct=")) {
						colours.put(BASECOLOUR_FOR_TAXIWAYS, hex);
					} else if (argLC.startsWith("-cr=")) {
						colours.put(BASECOLOUR_FOR_RUNWAYS, hex);
					} else if (argLC.startsWith("-cn=")) {
						colours.put(BASECOLOUR_FOR_NODES, hex);
					} else if (argLC.startsWith("-csn=")) {
						colours.put(BASECOLOUR_FOR_STANDNODES, hex);
					} else if (argLC.startsWith("-cbg=")) {
						colours.put(BACKGROUND_COLOUR, hex);
					} else {
						System.err.println("Unknown colour option in " + arg);
					}
				} else {
					System.err.println("Trouble parsing colour in " + arg);
				}
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
		if (bglxmlDataFile != null) System.out.println("BGLXML file: " + bglxmlDataFile);
		System.out.println((spacingForIntermediates >= 0) ? "Intermediates spaced at: " + spacingForIntermediates + "m" : "No intermediates");
		System.out.println((thresholdForSnapToNode > 0) ? "Threshold for snap to existing nodes: " + thresholdForSnapToNode + "m" : "Not snapping to existing nodes");
		System.out.println(includeRunways ? "Including runways in output" : "Excluding runways from output");
		System.out.println(((!addGateNodesAsStands) ? "NOT " : "") + "Adding OSM gate nodes as stands");
		System.out.println(((!addToNearestTaxiway) ? "NOT " : "") + "Adding stand nodes with no taxiway specified to nearest taxiway");
		System.out.println("Now processing...");
		
		if (!new File(osmDataFile).exists()) {
			System.err.println("OSM file not found: " + osmDataFile + ", quitting.");
			System.exit(1);
		}
		
		TaxiGen tg = new TaxiGen(standsDataFile, osmDataFile, thresholdForSnapToNode, spacingForIntermediates, addGateNodesAsStands, addToNearestTaxiway, bglxmlDataFile);
		
		if (checkConnectivity) {
			tg.checkConnectivityDialogue("DebugConnectivity.kml");
		}
		
		System.out.println("Writing GM file:" + outputGMFile);
		GroundMovementWriter gmw = tg.graphNodesAndEdgesToGMFile(false);
		gmw.writeFile(outputGMFile);
		
		if (edgeAnglesFile != null) {
			System.out.println("Writing edge angles:" + edgeAnglesFile);
			tg.graphEdgeAnglesToGMStyleFile(edgeAnglesFile, !includeRunways);
		}
		
		if (kmlFile != null) {
			System.out.println("Writing KML:" + kmlFile);
			tg.graphNodesAndEdgesToKML(kmlFile, !includeRunways, colours);
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
		System.out.println(" -conn=y/n : check graph connectivity? y/n (default = y)");
		System.out.println(" -nearest=y/n : if no 'nearest taxiway' is specified for a stand, add it to the nearest taxiway (default = n)");
		System.out.println(" -gn=y/n : add OSM gate nodes as stands, either connecting to the nearest taxiway, or the one specified in the \"stands\" text file (default = n)");
		System.out.println(" -bglxml=filename : if supplied, this will read stands from the specified bglxml file (ultimate goal will be to allow full parsing as alternative to OSM)");
		System.out.println(" -cp=oobbggrr : colour used for aircraft paths (hex values for opacity, blue, green and red) default is 2255ee00");
		System.out.println(" -cs=oobbggrr : colour used for stand edges (hex values for opacity, blue, green and red) default is 2255ee00");
		System.out.println(" -ct=oobbggrr : colour used for taxiway edges (hex values for opacity, blue, green and red) default is 2255ee00");
		System.out.println(" -cr=oobbggrr : colour used for runway edges (hex values for opacity, blue, green and red) default is 2255ee00");
		System.out.println(" -cn=oobbggrr : colour used for nodes (hex values for opacity, blue, green and red) default is 2255ee00");
		System.out.println(" -csn=oobbggrr : colour used for stand nodes (hex values for opacity, blue, green and red) default is 2255ee00");
		System.out.println(" -cbg=oobbggrr : colour used for blank background (hex values for opacity, blue, green and red) default is 2255ee00");
		System.out.println();
	}
	
	/** initialise taxiways object - load airport structure from OSM and NATS stand locations*/
	public TaxiGen(String standsDataFile, String osmDataFile, double thresholdForSnapToNode, double spacingForIntermediates, boolean addGateNodesAsStands, boolean addToNearestTaxiway, String bglxmlDataFile) {
		this.thresholdForSnapToNode = thresholdForSnapToNode;
		this.spacingForIntermediates = spacingForIntermediates;
		
		// each taxiway object is a list of edges making up a taxiway; here indexed by taxiway name
		Map<String,Taxiway> taxiways = new TreeMap<String, Taxiway>();
		
		Stands stands = null;
		if (standsDataFile != null) {
			System.out.println("Loading stand data");
			stands = new Stands(standsDataFile);
		}
		Map<String, Stand> standsWithNoCoords = (stands != null) ? stands.getStandsWithNoCoords() : null;
		
		// create edges from OSM data
		System.out.println("Loading OSM data");
		
		//the nodes from openstreetmap - covering runways and taxiways; indexed by node OSM ID
		Map<String, TaxiNode> osmNodes = new TreeMap<String,TaxiNode>();
		
		Map<String, TaxiNode> gateNodesToAddAsStands = null;
		if (addGateNodesAsStands) {
			gateNodesToAddAsStands = new TreeMap<>();
		}
		
		this.allEdges = new TreeSet<TaxiEdge>();
		taxiways.putAll(loadNodesFromOSM(osmDataFile, osmNodes, allEdges, standsWithNoCoords.keySet(), gateNodesToAddAsStands));
		
		// load and create nodes for gates from NATS data
		System.out.println("Adding NATS stands");
		Map<String, TaxiNode> addedStandNodes = (stands != null) ? stands.getStandNodes() : null;
		
		if (gateNodesToAddAsStands != null) {
			if (addedStandNodes == null) {
				addedStandNodes = gateNodesToAddAsStands;
			} else {
				addedStandNodes.putAll(gateNodesToAddAsStands); // this erases the taxiway attachments!
			}
		}
		
		if (bglxmlDataFile != null) {
			ParseBGLXML p = new ParseBGLXML(bglxmlDataFile);
			if (addedStandNodes == null) {
				addedStandNodes = p.getStandNodes();
			} else {
				addedStandNodes.putAll(p.getStandNodes());
			}
		}
		
		// for each gate node, find all edges related to the taxiway it's connected to, and
		// figure out the nearest point on each. Whichever is nearest overall, add an intersection node
		// (if one doesn't already exist within a radius of a couple of metres) and add an edge to the node
		// (also delete existing edge and add two new ones to include that node in the taxiway)

		// revised: have lots of intermediate nodes, and just look for the nearest one on the right taxiway
		Map<String,TaxiNode> gateAdditionalNodes = new TreeMap<String,TaxiNode>(); // the set of any extra nodes added to the OSM data to allow for stand connections
		if (addedStandNodes != null) {
			System.out.println("Adding nodes for added stands");
			Set<TaxiNode> standNodesWithSpecificAttachments = new TreeSet<TaxiNode>(); // keep these until the end so we can pick up generated stand attachment nodes too 
			for (TaxiNode tn : addedStandNodes.values()) {
				if (tn.getNodeAttachment() != null) {
					standNodesWithSpecificAttachments.add(tn);
				} else {
					// which taxiways is the node associated with?
					List<Taxiway> associatedTaxiways = new ArrayList<Taxiway>();
					
					Stand specifiedTaxiways = standsWithNoCoords.get(tn.getMeta());
					if ((tn.getAssociatedTaxiways().length == 0) && ((specifiedTaxiways == null) || (specifiedTaxiways.getAssociatedTaxiways().length == 0))) {
						System.out.println("No taxiways specified for stand node " + tn);
					}
					
					for (String tw : tn.getAssociatedTaxiways()) {
						if (taxiways.containsKey(tw.toUpperCase())) {
							associatedTaxiways.add(taxiways.get(tw.toUpperCase()));
						} else {
							System.out.println("Couldn't find taxiway " + tw + " for stand node " + tn);
						}
					}
					
					if (specifiedTaxiways != null) {
						for (String s : specifiedTaxiways.getAssociatedTaxiways()) {
							if (taxiways.containsKey(s.toUpperCase())) {
								associatedTaxiways.add(taxiways.get(s.toUpperCase()));
							} else {
								System.out.println("Couldn't find taxiway " + s + " for stand node " + tn);
							}
						}
					}
					
					if (addToNearestTaxiway) {
						Taxiway tw = findNearestTaxiway(taxiways.values(), tn);
						if (tw != null) {
							System.out.println("Adding " + tn + " to nearest taxiway, " + tw.getName());
							associatedTaxiways.add(tw);
						} else {
							System.out.println("Couldn't find a taxiway for stand node " + tn);
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
		
		this.runways = GraphManipulation.locateRunways(allEdges);
		
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
			if (te.getEdgeType() != TaxiEdge.EdgeType.RUNWAY) {
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
		
		this.runways = GraphManipulation.locateRunways(allEdges);
		
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
	
	/**
	 * this performs a process of connectivity checks on the whole airport graph
	 * if the graph is wholly connected, then nothing is changed.
	 * if not, a KML file (specified by filename) is written out to allow the user to debug the disconnected graph
	 * the user can choose which subgraph to retain (only one of them can be chosen), or to abort the whole process
	 * (choosing to retain one subgraph removes all other nodes from the final graph that will be written out)
	 */
	public void checkConnectivityDialogue(String filename) {
		ConnectivityInspector<TaxiNode, TaxiEdge> ci = new ConnectivityInspector<>(this.graphWholeAirport);
		
		if (!ci.isGraphConnected()) {
			List<Set<TaxiNode>> groups = ci.connectedSets();
			
			System.out.println("WARNING: the airport graph is not fully connected.");
			System.out.println("There are " + groups.size() + " distinct groups of connected nodes.");
			System.out.println("Writing out KML debug file showing the groups to " + filename + ", please wait...");
			
			graphNodesAndEdgesInGroupsToKML(filename, groups, allEdges);
			
			System.out.println("File Written. Please take a look.");
			System.out.println("You should either quit and amend the OSM source data before retrying,");
			System.out.println("or choose one of the groups...");
			System.out.println("Please type the number of a group (0-" + (groups.size()-1) + ", identified in the KML) or Q to quit.");
			
			boolean done = false;
			int choice = -1;
			while (!done) {
				try {
					BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
					String line = in.readLine();
					
					if (line.toLowerCase().startsWith("q")) {
						System.out.println("Okay, quitting.");
						System.exit(0);
					} else {
						try {
							choice = Integer.parseInt(line);
							
							if ((choice < 0) || (choice >= groups.size())) {
								System.out.println("Please enter just a number 0-" + (groups.size()-1) + " or 'q'.");
							} else {
								done = true;
							}
						} catch (NumberFormatException e) {
							System.out.println("Please enter just a number 0-" + (groups.size()-1) + " or 'q'.");
						}
					}
				} catch (IOException e) {
					System.out.println("Error encountered when reading from STDIN.");
					System.out.println("Going with group 0.");
					choice = 0;
					done = true;
				}
			}
			
			// tidy up the graph to match the user's choice.
			Set<TaxiNode> toRemove = new TreeSet<TaxiNode>();
			for (int i = 0; i < groups.size(); i++) {
				if (i != choice) {
					toRemove.addAll(groups.get(i));
				}
			}
			
			for (TaxiNode node : toRemove) {
				Set<TaxiEdge> s = this.graphWholeAirport.edgesOf(node);
				this.allEdges.removeAll(s);
				this.allTaxiEdges.removeAll(s);
				
				if (node.getNodeType() == NodeType.STAND) {
					this.standNodes.remove(node.getMeta());
				}
				
				this.allNodes.remove(node.getId());
			}

			this.allTaxiNodes.removeAll(toRemove);
			
			for (Runway rw : this.runways.values()) {
				rw.removeNodes(toRemove);
			}

			this.graphWholeAirport.removeAllVertices(toRemove);
			this.graphTaxiways.removeAllVertices(toRemove);
		}
	}
	
	public void graphNodesAndEdgesToKML(String filename, boolean excludeRunways, Map<String, String> colours) {
		if (excludeRunways) {
			graphNodesAndEdgesToKML(filename, allTaxiNodes, allTaxiEdges, colours);
		} else {
			graphNodesAndEdgesToKML(filename, allNodes.values(), allEdges, colours);
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
							double angle = Geography.angleBetweenEdges(te1, te2);
							
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
			TaxiEdge teS = new TaxiEdge("ES" + tnFrom.getId() + "-" + taxiways.get(0).getName(), taxiways.get(0), tnFrom, toAttachTo, Geography.distance(tnFrom, toAttachTo), TaxiEdge.EdgeType.STAND_CONNECTION);
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
			
			// loop over all edges on the given taxiway, loop over to look for nearest (Euclidean distance)
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
					double distance1 = Geography.distance(tnFrom, te.getTnFrom());
					double distance2 = Geography.distance(tnFrom, te.getTnTo());
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
					double distanceFromPointToNode1 = Geography.distance(te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate(), coords[0], coords[1]);
					double distanceFromPointToNode2 = Geography.distance(te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate(), coords[0], coords[1]);
					
					if (distanceFromPointToNode1 < thresholdForSnapToNode) {
						distance = Geography.distance(tnFrom, te.getTnFrom());
						nodeToUse = te.getTnFrom();
						beyondEndOfEdge = true; // set this so we'll use the node rather than the edge
					} else if (distanceFromPointToNode2 < thresholdForSnapToNode) {
						distance = Geography.distance(tnFrom, te.getTnTo());
						nodeToUse = te.getTnTo();
						beyondEndOfEdge = true; // set this so we'll use the node rather than the edge
					} else {
						distance = Geography.distance(tnFrom.getLatCoordinate(), tnFrom.getLonCoordinate(), coords[0], coords[1]);
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
			TaxiEdge teS = new TaxiEdge("ES" + tnFrom.getId() + "-" + taxiways.get(i).getName(), taxiways.get(i), tnFrom, tn, minDistances[i], TaxiEdge.EdgeType.STAND_CONNECTION);
			edgeSet.add(teS);
			
			// two new edges to join to the new edge, if we're meant to be replacing the edge
			if (edgesToReplace[i] != null) {
				TaxiEdge te1 = new TaxiEdge(edgesToReplace[i].getId(), taxiways.get(i), edgesToReplace[i].getTnFrom(), tn, Geography.distance(edgesToReplace[i].getTnFrom(), tn), TaxiEdge.EdgeType.TAXIWAY);
				TaxiEdge te2 = new TaxiEdge(edgesToReplace[i].getId(), taxiways.get(i), tn, edgesToReplace[i].getTnTo(), Geography.distance(tn, edgesToReplace[i].getTnTo()), TaxiEdge.EdgeType.TAXIWAY);
	
				edgeSet.remove(edgesToReplace[i]);
				edgeSet.add(te1);
				edgeSet.add(te2);
			
				// also amend taxiway object
				taxiways.get(i).replaceEdge(edgesToReplace[i], Arrays.asList(new TaxiEdge[]{te1, te2}));
			}
		}
	} // end of findNearestEdgeOnTaxiways
	
	/**quite similar findNearestEdgeOnTaxiways but just looks at distances*/
	private Taxiway findNearestTaxiway(Collection<Taxiway> allTaxiways, TaxiNode tn) {
		Taxiway closestTaxiway = null;
		double shortestDistance = Double.POSITIVE_INFINITY;
		
		for (Taxiway tw : allTaxiways) {
			// loop over all edges on the given taxiway, loop over to look for nearest (Euclidean distance)
			for (TaxiEdge te : tw.getEdges()) {
				// get nearest point on the edge
				double[] coords = nearestPointOnLine(tn.getLatCoordinate(), tn.getLonCoordinate(), te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate(), te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate());
				// NB coords are lat,lon
				
				// if the intersection coords are beyond the end of an edge, just use the node at the end of the edge
				// can just check one of the coordinates as it's a straight line
				boolean beyondEndOfEdge = (coords[0] <= Math.min(te.getTnFrom().getLatCoordinate(), te.getTnTo().getLatCoordinate())) || (coords[0] >= Math.max(te.getTnFrom().getLatCoordinate(), te.getTnTo().getLatCoordinate()));
				
				double distance;
				if (beyondEndOfEdge) {
					// which node is closest?
					double distance1 = Geography.distance(tn, te.getTnFrom());
					double distance2 = Geography.distance(tn, te.getTnTo());
					if (distance1 < distance2) {
						distance = distance1;
					} else {
						distance = distance2;
					}
				} else {
					// If on an edge, before we commit to checking whether to add a new node (breaking the edge in two), see if the end of the edge is pretty close.
					// If it is, just connect to that.
					double distanceFromPointToNode1 = Geography.distance(te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate(), coords[0], coords[1]);
					double distanceFromPointToNode2 = Geography.distance(te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate(), coords[0], coords[1]);
					
					if (distanceFromPointToNode1 < thresholdForSnapToNode) {
						distance = Geography.distance(tn, te.getTnFrom());
						beyondEndOfEdge = true; // set this so we'll use the node rather than the edge
					} else if (distanceFromPointToNode2 < thresholdForSnapToNode) {
						distance = Geography.distance(tn, te.getTnTo());
						beyondEndOfEdge = true; // set this so we'll use the node rather than the edge
					} else {
						distance = Geography.distance(tn.getLatCoordinate(), tn.getLonCoordinate(), coords[0], coords[1]);
					}
				}
				
				if (distance < shortestDistance) {
					shortestDistance = distance;
					closestTaxiway = tw;
				}
			}
		}
		
		return closestTaxiway;
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
	
	private static Map<String, Taxiway> loadNodesFromOSM(String filename, Map<String, TaxiNode> nodeStore, Set<TaxiEdge> edgeStore, Set<String> specifiedStandNames, Map<String, TaxiNode> addGateNodesAsStands) {
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
			//String standName = "";
			List<AeroWay> a = m.get(key);
			for (int i = 0; i < a.size(); i++) {
				AeroWay w = a.get(i);
				if ((w.type == Type.TAXIWAY) && (!specifiedStandNames.contains(w.name))) { // only count taxiways if they're not listed as being stands
					numTaxiways++;
				} else if (w.type == Type.RUNWAY) {
					runwayNames += ((numRunways==0)?"":",")+w.name;
					numRunways++;
				} else if ((w.type == Type.STAND) || (specifiedStandNames.contains(w.name))) {
					//standName = w.name;
					numStands++;
					
					/* used to figure out if this was a "stand" node here
					 * now done after we've loaded all the nodes for this way
					if (key == w.way.getWayNodes().get(w.way.getWayNodes().size() - 1).getNodeId()) { // that is, if this node is found at the end of the stand way
						endOfStandWay = true;
					}
					 */
				}
			}
			
			TaxiNode.NodeType type = null; // stand type is allocated elsewhere as we don't get that from OSM ()
			if ((numRunways > 0) && (numTaxiways > 0)) {
				type = NodeType.RUNWAY_CROSSING;
			/* see note re stands a few lines up from here
			} else if (endOfStandWay && (numStands > 0) && (numTaxiways == 0)) { // stand node is only the one at the end (not on the taxiway)
				type = NodeType.STAND;
			*/
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
				tn.setNumRunways(numRunways);
				tn.setNumStands(numStands);
				tn.setNumTaxiways(numTaxiways);
				nodeStore.put(tn.getId(), tn);

				// add names of runways associated with this node
				if ((type == NodeType.RUNWAY) || (type == NodeType.RUNWAY_CROSSING)) {
					String rw = tn.getMeta();
					rw += (rw.isEmpty()?"":",")+runwayNames;
					tn.setMeta(rw);
				}
				
				// this is now redundant as we determine which nodes are stands later
				/*
				if (type == NodeType.STAND) {
					tn.setMeta(standName);
				}
				*/
			}
		}
				
		// now iterate over all the taxiways, and add edges to the graph as appropriate
		for (AeroWay w : posm.getWays()) {
			if ((w.type == Type.TAXIWAY) || (w.type == Type.RUNWAY) || (w.type == Type.STAND)) {
				boolean stand = (w.type == Type.STAND) || specifiedStandNames.contains(w.name);
				Taxiway tw = null;
				
				if (!stand) { // not a stand, need to get the name from Way metadata
					tw = taxiways.get(w.name);

					if (tw == null) {
						tw = new Taxiway(w.name, w.type==Type.TAXIWAY ? Taxiway.Type.TAXIWAY : Taxiway.Type.RUNWAY);
						taxiways.put(w.name, tw);
					}
				} else { // is a stand: need to figure out where the end is
					// figure out which node is the "end" of the stand, that is, where the nosewheel sits
					// this will either be a node with nothing else attached (ie at the start or end of a single Way)
					// or it will be the last node of the stand Way (for stands with a drivethrough taxiway)
					// used to try figuring out this above when loading the nodes, but really need to load all of them first
					// to know which Ways they're associated with
					
					// OSM actually say that the last node in the stand's Way should be the stand itself
					// but particularly when the stands are taxiways, it seems to often be the wrong way round
					
					// are either of the end nodes only associate with 1 Way - that is, only one stand and no taxiways? (try the end one first)
					TaxiNode startNode = nodeStore.get("N" + w.way.getWayNodes().get(0).getNodeId());
					TaxiNode endNode = nodeStore.get("N" + w.way.getWayNodes().get(w.way.getWayNodes().size() - 1).getNodeId());
					
					TaxiNode standNode;
					if ((startNode.getNumTaxiways() == 0) && (startNode.getNumStands() == 1)) {
						standNode = startNode;
					} else if ((endNode.getNumTaxiways() == 0) && (endNode.getNumStands() == 1)) {
						standNode = endNode;
					} else { // otherwise, always use the last node in the Way for the stand (which is what OSM says should be the stand location) 
						standNode = endNode;
					}
					
					// update node's type and name
					standNode.setNodeType(NodeType.STAND);
					standNode.setMeta(w.name);
				}
				
				// get nodelist for this Way, and add an edge for each pair of nodes
				List<WayNode> l = w.way.getWayNodes();
				for (int i = 0; i < l.size() - 1; i++) {
					TaxiNode tnFrom = nodeStore.get("N" + l.get(i).getNodeId());
					TaxiNode tnTo = nodeStore.get("N" + l.get(i + 1).getNodeId()); // for info only: for stands/parking_positions, the latter node will always be the stand node (NB - sometimes stands are curved, so there are >1 edges in them; only really interested in the last one) 
					
					if (tnFrom != null && tnTo != null) { // if either is null, that's a node we didn't want to include (maybe it was runway only), so don't bother adding an adge to it
						double length = Geography.distance(tnFrom, tnTo);
						
						TaxiEdge te = new TaxiEdge("E" + w.way.getId(), tw, tnFrom, tnTo, length, (stand ? TaxiEdge.EdgeType.STAND_CONNECTION : (w.type==Type.TAXIWAY ? TaxiEdge.EdgeType.TAXIWAY : TaxiEdge.EdgeType.RUNWAY)));
						
						if (te.getEdgeType() == TaxiEdge.EdgeType.RUNWAY) {
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
		
		// now add all the "gate" nodes - if required
		if (addGateNodesAsStands != null) {
			Map<String, Node> gateNodes = posm.getGateNodes();
			for (Entry<String, Node> e : gateNodes.entrySet()) {
				Node n = e.getValue();
				TaxiNode tn = new TaxiNode("N" + n.getId(), TaxiNode.NodeType.STAND, n.getLatitude(), n.getLongitude());
				tn.setMeta(e.getKey());
				addGateNodesAsStands.put(tn.getMeta(), tn);
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
				    			toAdd.add(new TaxiEdge(te.getId(), tw, te.getTnFrom(), intermediateNodes[i], Geography.distance(te.getTnFrom(), intermediateNodes[i]), TaxiEdge.EdgeType.TAXIWAY));
				    		} else {
				    			toAdd.add(new TaxiEdge(te.getId(), tw, intermediateNodes[i - 1], intermediateNodes[i], Geography.distance(intermediateNodes[i - 1], intermediateNodes[i]), TaxiEdge.EdgeType.TAXIWAY));
				    		}
				    		
				    		// add an edge for the last node too
				    		if (i == intermediateNodes.length - 1) {
			    				toAdd.add(new TaxiEdge(te.getId(), tw, intermediateNodes[i], te.getTnTo(), Geography.distance(intermediateNodes[i], te.getTnTo()), TaxiEdge.EdgeType.TAXIWAY));
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
	
    private void graphNodesAndEdgesToKML(String filename, Collection<TaxiNode> nodes, Collection<TaxiEdge> edges, Map<String, String> colours) {
		final Kml kml = KmlFactory.createKml();
		final Document document = kml.createAndSetDocument().withName(filename).withOpen(true);
		final Document documentTaxiways = document.createAndAddDocument().withName(filename + "Taxiways").withOpen(true);
		final Document documentNodes = document.createAndAddDocument().withName(filename + "Nodes").withOpen(true);

		final Style styleOSM = documentNodes.createAndAddStyle().withId("placemarkStyle");
		styleOSM.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor(colours.get(BASECOLOUR_FOR_NODES)).withScale(1);
		final Style styleOSMGates = documentNodes.createAndAddStyle().withId("placemarkStyleGates");
		styleOSMGates.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor(colours.get(BASECOLOUR_FOR_STANDNODES)).withScale(1);
		
		final Style styleTaxiway = documentTaxiways.createAndAddStyle().withId("linestyleTaxiway");
		styleTaxiway.createAndSetLineStyle()
		.withColor(colours.get(BASECOLOUR_FOR_TAXIWAYS))
		.withWidth(4.0d);

		final Style styleRunway = documentTaxiways.createAndAddStyle().withId("linestyleRunway");
		styleRunway.createAndSetLineStyle()
		.withColor(colours.get(BASECOLOUR_FOR_RUNWAYS))
		.withWidth(4.0d);
		
		final Style styleTaxiwayToGate = documentTaxiways.createAndAddStyle().withId("linestyleTaxiwayToGate");
		styleTaxiwayToGate.createAndSetLineStyle()
		.withColor(colours.get(BASECOLOUR_FOR_STANDS))
		.withWidth(4.0d);

		for (TaxiEdge te : edges) {
			String style = "#linestyle";
			if (te.getEdgeType() == TaxiEdge.EdgeType.TAXIWAY) {
				style += "Taxiway";
			} else if (te.getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) {
				style += "TaxiwayToGate";
			} else {
				style += "Runway";
			}
			
			// name for edges is "GMID type [node1id>>>node2id] bearing"
			String name = this.gmwIDsForTaxiEdges.get(te) + " " + te.getEdgeType() + " ["+te.getTnFrom().getId()+">>>"+te.getTnTo().getId()+"] " + Maths.roundDouble(Geography.bearing(te), 2) + "deg";
			LineString ls = documentTaxiways.createAndAddPlacemark().withName(name).withStyleUrl(style)
			.createAndSetLineString(); //.withExtrude(true); //.withTessellate(true);

			ls.addToCoordinates(te.getTnFrom().getLonCoordinate() + "," + te.getTnFrom().getLatCoordinate() + ",0");
			ls.addToCoordinates(te.getTnTo().getLonCoordinate() + "," + te.getTnTo().getLatCoordinate() + ",0");
		}
		
		for (TaxiNode tn : nodes) {
			String style = tn.getNodeType() == NodeType.STAND ? "#placemarkStyleGates" : "#placemarkStyle";
			String meta = (tn.getMeta() != null) && (!tn.getMeta().isEmpty()) ? " (" + tn.getMeta() + ")" : "";
			String name = this.gmwIDsForTaxiNodes.get(tn).toString() + " " + tn.getNodeType() + (tn.getNodeType()==NodeType.INTERMEDIATE?"":" (" + tn.getId() + ")") + meta; // name for nodes is "GMID type OSMID (Meta)" - if type is intermediate there's no OSM ID
			Placemark p = documentNodes.createAndAddPlacemark().withName(name).withStyleUrl(style);
			p.createAndSetPoint().addToCoordinates(tn.getLonCoordinate(), tn.getLatCoordinate());
		}
		
		KMLUtils.addGroundOverlayToKMLDocument(filename, document, colours.get(BACKGROUND_COLOUR));
		
		try {
			kml.marshal(new File(filename));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
    
    private static void graphNodesAndEdgesInGroupsToKML(String filename, List<Set<TaxiNode>> nodes, Collection<TaxiEdge> edges) {
		final Kml kml = KmlFactory.createKml();
		final Document document = kml.createAndSetDocument().withName(filename).withOpen(true);
		final Document documentTaxiways = document.createAndAddDocument().withName(filename + "Taxiways").withOpen(false);
		final Document documentNodes = document.createAndAddDocument().withName(filename + "Nodes").withOpen(true);
		Document[] documentNodeGroups = new Document[nodes.size()];
		for (int i = 0; i < documentNodeGroups.length; i++) {
			documentNodeGroups[i] = documentNodes.createAndAddDocument().withName("Group " + i).withOpen(false);
		}

		final Style styleOSM = documentNodes.createAndAddStyle().withId("placemarkStyle");
		styleOSM.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor("ff00ffff").withScale(1);
		final Style styleOSMGates = documentNodes.createAndAddStyle().withId("placemarkStyleGates");
		styleOSMGates.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor("ffff0000").withScale(1);
		
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
			if (te.getEdgeType() == TaxiEdge.EdgeType.TAXIWAY) {
				style += "Taxiway";
			} else if (te.getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) {
				style += "TaxiwayToGate";
			} else {
				style += "Runway";
			}
			
			LineString ls = documentTaxiways.createAndAddPlacemark().withName(te.getUniqueString() + "["+te.getTnFrom().getId()+">>>"+te.getTnTo().getId()+"]" + Geography.bearing(te)).withStyleUrl(style)
			.createAndSetLineString(); //.withExtrude(true); //.withTessellate(true);

			ls.addToCoordinates(te.getTnFrom().getLonCoordinate() + "," + te.getTnFrom().getLatCoordinate() + ",0");
			ls.addToCoordinates(te.getTnTo().getLonCoordinate() + "," + te.getTnTo().getLatCoordinate() + ",0");
		}
		
		for (int i = 0; i < documentNodeGroups.length; i++) {
			for (TaxiNode tn : nodes.get(i)) {
				String style = tn.getNodeType() == NodeType.STAND ? "#placemarkStyleGates" : "#placemarkStyle";
				String meta = (tn.getMeta() != null) && (!tn.getMeta().isEmpty()) ? " (" + tn.getMeta() + ")" : "";
				Placemark p = documentNodeGroups[i].createAndAddPlacemark().withName(tn.getId() + meta).withStyleUrl(style);
				p.createAndSetPoint().addToCoordinates(tn.getLonCoordinate(), tn.getLatCoordinate());
			}
		}
		
		KMLUtils.addGroundOverlayToKMLDocument(filename, document);
		
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
			if (te.getEdgeType() == TaxiEdge.EdgeType.RUNWAY) {
				spec = GroundMovementWriter.Edge.Specification.runway;
			} else if (te.getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) {
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
}
