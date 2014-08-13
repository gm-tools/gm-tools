package gmtools.common;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 * <br/><br/>
 * writes out files in the same format as Notts GM benchmarks
 * see http://www.asap.cs.nott.ac.uk/external/atr/benchmarks/gmFormatting.shtml
 * TODO Turn_delays and separations not implemented
 */
public class GroundMovementWriter {
	private int separationDistanceOnGround;
	private int freezingTime;
	private int horizonLength;
	private List<Node> nodes;
	private List<Edge> edges;
	private List<Route> routes;
	private List<Aircraft> aircraft;
	
	/**initialise new blank GM file*/
	public GroundMovementWriter() {
		this.separationDistanceOnGround = -1;
		this.freezingTime = -1;
		this.horizonLength = -1;
		this.nodes = new ArrayList<Node>();
		this.edges = new ArrayList<Edge>();
		this.routes = new ArrayList<Route>();
		this.aircraft = new ArrayList<Aircraft>();
	}
	
	/**initialise based on an existing GM file*/
	public GroundMovementWriter(String filename) {
		this();
		readFile(filename);
	}
	
	public void setSeparationDistanceOnGround(int separationDistanceOnGround) {
		this.separationDistanceOnGround = separationDistanceOnGround;
	}
	
	public void setFreezingTime(int freezingTime) {
		this.freezingTime = freezingTime;
	}
	
	public void setHorizonLength(int horizonLength) {
		this.horizonLength = horizonLength;
	}
	
	public void addNode(Node n) {
		this.nodes.add(n);
	}
	
	public void addEdge(Edge e) {
		this.edges.add(e);
	}
	
	public void addRoute(Route r) {
		this.routes.add(r);
	}
	
	public void addAircraft(Aircraft a) {
		this.aircraft.add(a);
	}
	
	public List<Edge> getEdges() {
		return edges;
	}
	
	public List<Node> getNodes() {
		return nodes;
	}
	
	public Node getNodeWithID(int id) {
		Node n = nodes.get(id - 1); // try this first, it's probably here
		if (n.getSeqNo() == id) {
			return n;
		} else {
			for (Node n2 : nodes) {
				if (n2.getSeqNo() == id) {
					return n2;
				}
			}
		}
		
		return null;
	}
	
	public Edge getEdgeWithID(int id) {
		Edge e = edges.get(id - 1); // try this first, it's probably here
		if (e.getSeqNo() == id) {
			return e;
		} else {
			for (Edge e2 : edges) {
				if (e2.getSeqNo() == id) {
					return e2;
				}
			}
		}
		
		return null;
	}
	
	public List<Aircraft> getAircraft() {
		return aircraft;
	}
	
	public List<Route> getRoutes() {
		return routes;
	}
	
	public Route getRoute(int id) {
		for (Route r : this.routes) {
			if (r.getSeqNo() == id) {
				return r;
			}
		}
		
		return null;
	}
	
	/**load aircraft details from manchester airport data*/
	public void loadAircraftFromMANData(String filename) {
		// TODO
	}
	
	public void writeFile(String filename) {
		try {
			PrintStream out = new PrintStream(new FileOutputStream(filename));
			
			out.println("%SECTION%1%;General;");
			String generalFields = "%FIELDS%;";
			String generalDescription = "%DESCRIPTION%;";
			String generalValues = ";";
			if (this.separationDistanceOnGround >= 0) {
				generalFields += "separation_distance_on_ground;";
				generalDescription += "[m];";
				generalValues += this.separationDistanceOnGround + ";";
			}
			if (this.freezingTime >= 0) {
				generalFields += "freezing_time;";
				generalDescription += "[s];";
				generalValues += this.freezingTime + ";";
			}
			if (this.horizonLength >= 0) {
				generalFields += "horizon_length;";
				generalDescription += "[s];";
				generalValues += this.horizonLength + ";";
			}
			out.println(generalFields);
			out.println(generalDescription);
			out.println(generalValues);
			
			if (!this.nodes.isEmpty()) {
				out.println("%SECTION%1%;Nodes;");
				// do any nodes have a "specification" or lat/lon? if not, don't add in fields for them 
				boolean hasSpec = false;
				for (int i = 0; !hasSpec && i < nodes.size(); i++) {
					if (nodes.get(i).specification != null) {
						hasSpec = true;
					}
				}
				boolean hasLatLon = true;
				for (int i = 0; hasLatLon && i < nodes.size(); i++) {
					if (Double.isNaN(nodes.get(i).latitude) || Double.isNaN(nodes.get(i).longitude)) {
						hasLatLon = false;
					}
				}
				String nodesFields = "%FIELDS%;node_id;x;y;" + (hasLatLon ? "lat;lon;" : "") + "name;" + (hasSpec ? "specification;" : "");
				String nodesDescription = "%DESCRIPTION%;ID;x-coordination;y-coordination;" + (hasLatLon ? "latitude;longitude;" : "") + "name;" + (hasSpec ? "{'', gate, runway, holding_point, intermediate};" : "");
				out.println(nodesFields);
				out.println(nodesDescription);
				for (Node n : this.nodes) {
					out.println(";" + n.seqNo + ";" + n.x + ";" + n.y + ";" + (hasLatLon ? n.latitude + ";" + n.longitude + ";" : "") + n.name + ";" + (hasSpec ? (n.specification != null ? n.specification + ";" : ";") : ""));
				}
			}
			
			if (!this.edges.isEmpty()) {
				out.println("%SECTION%1%;Edges;");
				// if all traversal times are a single value, amend column type
				boolean singleValue = true;
				boolean hasTraversalTimes = false;
				for (int i = 0; singleValue && i < this.edges.size(); i++) {
					if (edges.get(i).traversalTimes.length > 1) {
						singleValue = false;
					}
					if (edges.get(i).traversalTimes.length > 0) {
						hasTraversalTimes = true;
					}
				}
				boolean hasNames = false;
				for (int i = 0; !hasNames && i < this.edges.size(); i++) {
					if ((edges.get(i).name != null) && !edges.get(i).name.isEmpty()) {
						hasNames = true;
					}
				}
				
				String edgesFields = "%FIELDS%;edge_id;start_node;end_node;directed;length;specification;" + (hasTraversalTimes?"traversal_time;":"") + (hasNames?"name;":"");
				String edgesDescription = "%DESCRIPTION%;ID;node_id;node_id;0 for undirected, 1 for directed;[m];{gate,runway,taxiway,taxiwayrunway,other};" + (hasTraversalTimes ? (singleValue ? "[s];" : "in alphanumerical order of 'speed_profile', [s];"):"") + (hasNames?"name;":"");
				out.println(edgesFields);
				out.println(edgesDescription);
				
				for (Edge e : this.edges) {
					String times = "";
					if (e.traversalTimes.length > 0) {
						if (e.traversalTimes.length == 1) {
							times = ";" + e.traversalTimes[0];
						} else {
							times = ";[";
							for (double t : e.traversalTimes) {
								times += t + ",";
							}
							times += "]";
						}
						times += ";";
					}
					out.println(";" + e.seqNo + ";" + e.startNode + ";" + e.endNode + ";" + (e.directed?"1":"0") + ";" + e.length + ";" + e.specification + times + (hasNames?e.name+";":""));
				}
			}
			
			if (!this.routes.isEmpty()) {
				out.println("%SECTION%1%;Routes;");
				out.println("%FIELDS%;route_id;path;");
				out.println("%DESCRIPTION%;ID;[set of edge_id];");
				for (Route r : this.routes) {
					out.println(";" + r.seqNo + ";[" + ArrayTools.toString(r.path, ",") + "];");
				}
			}
			
			if (!this.aircraft.isEmpty()) {
				boolean includingRouteIDs = false;
				boolean includingRunways = false;
				for (int i = 0; !includingRouteIDs && !includingRunways && (i < this.aircraft.size()); i++) {
					if ((this.aircraft.get(i).routeIDs != null) && (this.aircraft.get(i).routeIDs.length > 0)) {
						includingRouteIDs = true;
					}
					if (this.aircraft.get(i).runwayUsed != null) {
						includingRunways = true;
					}
				}
				
				out.println("%SECTION%1%;Aircraft;");
				out.println("%FIELDS%;aircraft_id;type;start_node;end_node;start_time;end_time;appearance_time;" + (includingRouteIDs ? "routes;" : "") + (includingRunways ? "runway;" : "") + "speed_profile;speed_min;speed_ideal;speed_max;weight_class;sid_route;take-off_speed_group");
				out.println("%DESCRIPTION%;ID;{arrival;departure;other};node_id;node_id;[earliest,scheduled,latest];[earliest,scheduled,latest];[s];" + (includingRouteIDs ? "single value or list of route_id;" : "") + (includingRunways ? "runway_name;" : "") + "for traversal time;[m/s];[m/s];[m/s];for separation constraints;for separation constraints;for separation constraints");
				// if all start/end time are a single figure, just include that
				for (Aircraft a : this.aircraft) {
					String routeIDString = includingRouteIDs ? "[" + ArrayTools.toString(a.routeIDs, ",") + "];" : "";
					String runwayString = includingRunways ? a.runwayUsed + ";" : "";
					out.println(";" + a.seqNo + ";" + a.type + ";" + a.startNode + ";" + a.endNode + ";[" + ArrayTools.toString(a.startTime, ",") + "];[" + ArrayTools.toString(a.endTime, ",") + "];" + a.appearanceTime + ";" + routeIDString + runwayString + a.speedProfile + ";" + a.speedMin + ";" + a.speedIdeal + ";" + a.speedMax + ";" + a.weightClass + ";" + a.sidRoute + ";" + a.takeoffSpeedGroup);
				}
			}
			
			out.println("%END");
			out.close();
		} catch (IOException e) {
			System.err.println("Error writing out file");
			e.printStackTrace();
		}
	}
	
	private void readFile(String filename) {
		try {
			BufferedReader in = new BufferedReader(new FileReader(filename));
			
			int stage = -1; // stage 0=nodes,1=edges,2=aircraft
			Map<String,Integer> indices = new HashMap<String,Integer>(); // somewhere to keep column indices
			String line;
			while ((line = in.readLine()) != null) {
				//System.out.println(line);
				if (line.startsWith("%SECTION%")) {
					if (line.contains("Nodes")) {
						stage = 0;
					} else if (line.contains("Edges")) {
						stage = 1;
					} else if (line.contains("Aircraft")) { // this is done elsewhere at the moment
						stage = 2;
					} else if (line.contains("General")) {
						stage = 3;
					} else if (line.contains("Routes")) {
						stage = 4;
					} else {
						stage = -1; // ignore other data
					}
				} else if (line.startsWith("%FIELDS%")) {
					indices.clear();
					String[] headings = line.split(";", -1);
					for (int i = 0; i < headings.length; i++) {
						indices.put(headings[i], i);
					}
				} else if (line.startsWith("%DESCRIPTION%")) {
					/*do nothing?*/
				} else if (line.startsWith("%")) {
					/*do nothing?*/
				} else { // a data line
					String[] cols = line.split(";", -1); // -1 means apply pattern as many times as possible, so capturing empty fields at end
					
					if (stage == 0) {
						String id = cols[indices.get("node_id")];
						
						double x = 0;
						double y = 0;
						if (indices.containsKey("x")) {
							x = Double.parseDouble(cols[indices.get("x")]);
						}
						if (indices.containsKey("y")) {
							y = Double.parseDouble(cols[indices.get("y")]);
						}
						
						Node n = new Node(x, y);
						n.setSeqNo(Integer.parseInt(id)); // override default ID
						if (indices.containsKey("specification")) {
							String spec = cols[indices.get("specification")];
							n.specification = Node.Specification.valueOf(spec);
						}
						if(indices.containsKey("name")) {
							String name = cols[indices.get("name")];
							if ((name != null) && !name.isEmpty()) {
								n.name = name;
							}
						}

						if (indices.containsKey("lon")) {
							n.longitude = Double.parseDouble(cols[indices.get("lon")]);
						}
						if (indices.containsKey("lat")) {
							n.latitude = Double.parseDouble(cols[indices.get("lat")]);
						}
						
						addNode(n);
					} else if (stage == 1) {
						String id = cols[indices.get("edge_id")];
						int source = -1;
						int destination = -1;
						boolean directed = false;
						if (indices.containsKey("start_node")) {
							source = Integer.parseInt(cols[indices.get("start_node")]);
						}
						if (indices.containsKey("end_node")) {
							destination = Integer.parseInt(cols[indices.get("end_node")]);
						}
						if (indices.containsKey("directed")) {
							directed = cols[indices.get("directed")].contains("1");
						}
						double length = 0;
						if (indices.containsKey("length")) {
							length = Double.parseDouble(cols[indices.get("length")]);
						}
						double[] traversalTimes = null;
						if (indices.containsKey("traversal_time")) {
							String[] s = cols[indices.get("traversal_time")].replace("[", "").replace("]", "").split(",");
							if (s.length > 1) {
								traversalTimes = new double[s.length];
								for (int i = 0; i < s.length; i++) {
									traversalTimes[i] = Double.parseDouble(s[i]);
								}
							}
						}
						Edge.Specification specification = null;
						if (indices.containsKey("specification")) {
							specification = Edge.Specification.valueOf(cols[indices.get("specification")]);
						}
						String name = "";
						if (indices.containsKey("name")) {
							name = cols[indices.get("name")];
						}
						Edge te = new Edge(source, destination, directed, length, traversalTimes, specification, name);
						te.setSeqNo(Integer.parseInt(id));
						
						/* currently ignore taxi times in file
						if (indices.containsKey("traversal_time")) {
							String[] ts = cols[indices.get("traversal_time")].replace("[", "").replace("]", "").split(",");
							double[] times = new double[ts.length];
							for (int i = 0; i < times.length; i++) {
								times[i] = Double.parseDouble(ts[i]);
							}
							te.setTraversalTimes(times);
						} else {
							te.setTraversalTimes(new double[]{1});
						}
						*/
						this.addEdge(te);
					} else if (stage == 2) { // TODO to be completed: only crucial data read for now
						int id = Integer.parseInt(cols[indices.get("aircraft_id")]);
						
						Aircraft.Type type = Aircraft.Type.valueOf(cols[indices.get("type")]);
						
						int origin = Integer.parseInt(cols[indices.get("start_node")]);
						int destination = Integer.parseInt(cols[indices.get("end_node")]);
						
						String strOriginTimes = cols[indices.get("start_time")];
						String[] originTimes = strOriginTimes.substring(1, strOriginTimes.length() - 1).replaceAll("inf", "-1").split(","); // strip [] and split by commas
						long originEarliestTime = Long.parseLong(originTimes[0]);
						long originScheduledTime = Long.parseLong(originTimes[1]);
						long originLatestTime = Long.parseLong(originTimes[2]);
						
						String strDestinationTimes = cols[indices.get("end_time")];
						String[] destinationTimes = strDestinationTimes.substring(1, strDestinationTimes.length() - 1).replaceAll("inf", "-1").split(","); // strip [] and split by commas
						long destinationEarliestTime = Long.parseLong(destinationTimes[0]);
						long destinationScheduledTime = Long.parseLong(destinationTimes[1]);
						long destinationLatestTime = Long.parseLong(destinationTimes[2]);
						
						int speedProfile = Integer.parseInt(cols[indices.get("speed_profile")]);

						Aircraft a = new Aircraft(type, origin, destination, new long[]{originEarliestTime,originScheduledTime,originLatestTime}, new long[]{destinationEarliestTime,destinationScheduledTime,destinationLatestTime}, speedProfile);
						a.setSeqNo(id);

						if (indices.containsKey("routes")) {
							String routeIDs = cols[indices.get("routes")];
							String[] routeIDsSplit = routeIDs.replace("[", "").replace("]", "").split(",");
							int[] intRouteIDs = new int[routeIDsSplit.length];
							for (int i = 0; i < routeIDsSplit.length; i++) {
								intRouteIDs[i] = Integer.parseInt(routeIDsSplit[i]);
							}
							a.routeIDs = intRouteIDs;
						}
						
						if (indices.containsKey("runway")) {
							String runway = cols[indices.get("runway")];
							a.runwayUsed = runway;
						}
						
						addAircraft(a);
					} else if (stage == 3) { // general
						if (indices.containsKey("separation_distance_on_ground")) {
							this.separationDistanceOnGround = Integer.parseInt(cols[indices.get("separation_distance_on_ground")]);
						}
						
						if (indices.containsKey("freezing_time")) {
							this.freezingTime = Integer.parseInt(cols[indices.get("freezing_time")]);
						}
						
						if (indices.containsKey("horizon_length")) {
							this.horizonLength = Integer.parseInt(cols[indices.get("horizon_length")]);
						}
					} else if (stage == 4) { // routes
						// TODO
						String strRouteID = cols[indices.get("route_id")];
						int id = Integer.parseInt(strRouteID);
						String strPath = cols[indices.get("path")];
						String[] strPathCols = strPath.replace("[", "").replace("]", "").split(",");
						int[] intPath = new int[strPathCols.length];
						for (int i = 0; i < intPath.length; i++) {
							intPath[i] = Integer.parseInt(strPathCols[i]);
						}
						
						Route r = new Route(intPath);
						r.setSeqNo(id);
						
						addRoute(r);
					}
				}
			} // end of loop over file
			
			in.close();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	public static class Node {
		public enum Specification { gate, runway, holding_point, intermediate }
		private static int SEQ_NO = 1;
		private int seqNo;
		private double x;
		private double y;
		private double latitude;
		private double longitude;
		private Specification specification;
		private String name;
		public Node(String name, double x, double y, double latitude, double longitude, Specification specification) {
			this.name = name;
			this.x = x;
			this.y = y;
			this.latitude = latitude;
			this.longitude = longitude;
			this.specification = specification;
			this.seqNo = SEQ_NO++;
		}
		public Node(String name, double x, double y, Specification specification) {
			this(name, x, y, Double.NaN, Double.NaN, specification);
		}
		public Node(double x, double y) {
			this("", x, y, null);
		}
		public int getSeqNo() {
			return seqNo;
		}
		public double getLatitude() {
			return latitude;
		}
		public double getLongitude() {
			return longitude;
		}
		public Specification getSpecification() {
			return specification;
		}
		public String getName() {
			return name;
		}
		
		/**override seqNo (only used for reading an existing GM file)*/
		private void setSeqNo(int seqNo) {
			this.seqNo = seqNo;
			SEQ_NO = Math.max(SEQ_NO, seqNo + 1); // make sure that if we need to generate any more IDs, we won't clash with the new one 
		}
	}
	
	public static class Edge {
		private static int SEQ_NO = 1;
		private int seqNo;
		private int startNode;
		private int endNode;
		private boolean directed;
		private double length;
		private double[] traversalTimes;
		private String name;
		public enum Specification { gate, runway, taxiway, taxiwayrunway, other }
		public Specification specification;
		public Edge(int startNode, int endNode, boolean directed, double length, double[] traversalTimes, Specification specification, String name) {
			this.startNode = startNode;
			this.endNode = endNode;
			this.directed = directed;
			this.length = length;
			if (traversalTimes != null) {
				this.traversalTimes = traversalTimes;
			} else {
				this.traversalTimes = new double[] {length};
			}
			this.specification = specification;
			this.name = name;
			this.seqNo = SEQ_NO++;
		}
		public int getSeqNo() {
			return seqNo;
		}
		public Specification getSpecification() {
			return specification;
		}
		public int getStartNode() {
			return startNode;
		}
		public int getEndNode() {
			return endNode;
		}
		public double getLength() {
			return length;
		}
		public String getName() {
			return name;
		}
		
		/**override seqNo (only used for reading an existing GM file)*/
		private void setSeqNo(int seqNo) {
			this.seqNo = seqNo;
			SEQ_NO = Math.max(SEQ_NO, seqNo + 1); // make sure that if we need to generate any more IDs, we won't clash with the new one 
		}
	}
	
	public static class Route {
		private static int SEQ_NO = 1;
		private int seqNo;
		private int[] path;
		public Route(int[] path) {
			this.path = path;
			this.seqNo = SEQ_NO++;
		}
		public int getSeqNo() {
			return seqNo;
		}
		public int[] getPath() {
			return path;
		}
		
		/**override seqNo (only used for reading an existing GM file)*/
		private void setSeqNo(int seqNo) {
			this.seqNo = seqNo;
			SEQ_NO = Math.max(SEQ_NO, seqNo + 1); // make sure that if we need to generate any more IDs, we won't clash with the new one 
		}
	}
	
	public static class Aircraft {
		public enum Type { arrival, departure, other}
		private static int SEQ_NO = 1;
		private Type type;
		private int seqNo;
		private int startNode;
		private int endNode;
		private long[] startTime;
		private long[] endTime;
		private int appearanceTime;
		private int[] routeIDs;
		private int speedProfile;
		private double speedMin;
		private double speedIdeal;
		private double speedMax;
		private int weightClass;
		private int sidRoute;
		private int takeoffSpeedGroup;
		private String runwayUsed;
		
		/**
		 * @param type
		 * @param startNode
		 * @param endNode
		 * @param startTime [earliest,scheduled,latest]
		 * @param endTime [earliest,scheduled,latest]
		 * @param speedProfile [index into traversal times]
		 */
		public Aircraft(Type type, int startNode, int endNode, long[] startTime, long[] endTime, int speedProfile) {
			this.type = type;
			this.startNode = startNode;
			this.endNode = endNode;
			this.startTime = startTime;
			this.endTime = endTime;
			this.speedProfile = speedProfile;
			this.seqNo = SEQ_NO++;
			
			this.appearanceTime = 0;
			this.routeIDs = null;
			this.speedMin = 1;
			this.speedIdeal = 1;
			this.speedMax = 1;
			this.weightClass = 1;
			this.sidRoute = 1;
			this.takeoffSpeedGroup = 1;
			this.runwayUsed = null;
		}
		
		public void setRouteIDs(int[] routeIDs) {
			this.routeIDs = routeIDs;
		}

		public void setAppearanceTime(int appearanceTime) {
			this.appearanceTime = appearanceTime;
		}

		public void setSpeedMin(double speedMin) {
			this.speedMin = speedMin;
		}

		public void setSpeedIdeal(double speedIdeal) {
			this.speedIdeal = speedIdeal;
		}

		public void setSpeedMax(double speedMax) {
			this.speedMax = speedMax;
		}

		public void setWeightClass(int weightClass) {
			this.weightClass = weightClass;
		}

		public void setSidRoute(int sidRoute) {
			this.sidRoute = sidRoute;
		}

		public void setTakeoffSpeedGroup(int takeoffSpeedGroup) {
			this.takeoffSpeedGroup = takeoffSpeedGroup;
		}
		
		public void setRunwayUsed(String runwayUsed) {
			this.runwayUsed = runwayUsed;
		}
		
		/**override seqNo (only used for reading an existing GM file)*/
		private void setSeqNo(int seqNo) {
			this.seqNo = seqNo;
			SEQ_NO = Math.max(SEQ_NO, seqNo + 1); // make sure that if we need to generate any more IDs, we won't clash with the new one 
		}

		public Type getType() {
			return type;
		}

		public void setType(Type type) {
			this.type = type;
		}

		public int getStartNode() {
			return startNode;
		}

		public void setStartNode(int startNode) {
			this.startNode = startNode;
		}

		public int getEndNode() {
			return endNode;
		}

		public void setEndNode(int endNode) {
			this.endNode = endNode;
		}

		public long[] getStartTime() {
			return startTime;
		}

		public void setStartTime(long[] startTime) {
			this.startTime = startTime;
		}

		public long[] getEndTime() {
			return endTime;
		}

		public void setEndTime(long[] endTime) {
			this.endTime = endTime;
		}

		public int getSpeedProfile() {
			return speedProfile;
		}

		public void setSpeedProfile(int speedProfile) {
			this.speedProfile = speedProfile;
		}

		public int getSeqNo() {
			return seqNo;
		}

		public int getAppearanceTime() {
			return appearanceTime;
		}
		
		public int[] getRouteIDs() {
			return routeIDs;
		}

		public double getSpeedMin() {
			return speedMin;
		}

		public double getSpeedIdeal() {
			return speedIdeal;
		}

		public double getSpeedMax() {
			return speedMax;
		}

		public int getWeightClass() {
			return weightClass;
		}

		public int getSidRoute() {
			return sidRoute;
		}

		public int getTakeoffSpeedGroup() {
			return takeoffSpeedGroup;
		}
		
		public String getRunwayUsed() {
			return runwayUsed;
		}
	}
}
