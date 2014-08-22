package gmtools.snaptracks;

import gmtools.common.ArrayTools;
import gmtools.common.Geography;
import gmtools.common.KMLUtils;
import gmtools.common.Maths;
import gmtools.graph.TaxiEdge;
import gmtools.graph.TaxiEdge.EdgeType;
import gmtools.graph.TaxiNode;
import gmtools.graph.TaxiNode.NodeType;
import gmtools.parsers.RawFlightTrackData.Aircraft;
import gmtools.parsers.RawFlightTrackData.TimeCoordinate;
import gmtools.snaptracks.SnapTracksThread.Snapping.CoordTime;
import gmtools.tools.TaxiGen;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.alg.KShortestPaths;
import org.jgrapht.graph.WeightedMultigraph;

import uk.me.jstott.jcoord.LatLng;
import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Icon;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.KmlFactory;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Style;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class SnapTracksThread extends Thread {
	public static final int SNAPPED_TRACKS_OUTPUT_COL_FLIGHT = 1; // col 0 is thread number
	public static final String SNAPPED_TRACKS_OUTPUT_SUBSEPARATOR = ",";
	public static final String SNAPPED_TRACKS_OUTPUT_SEPARATOR = "\t";
	public static final int SNAPPED_TRACKS_OUTPUT_COL_CALLSIGN = 2;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_AIRLINE = 3;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_ORIGIN = 4;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_DESTINATION = 5;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_AIRCRAFT = 6;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_STANDNAMES = 7;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_RUNWAYNAMES = 8;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_SNAPPEDSUCCESSFULLY = 9;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_LATLONADDEDTOSNAP = 10;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_PATH = 11;
	public static final int SNAPPED_TRACKS_OUTPUT_COL_FIRST_TIMESTAMP = 12;
	
	public static final String EDGETIMESDETAILS_OUT_SEPARATOR = "\t";

	// this is the minimum fraction of coordinates snapped for a route to be valid at the first stage
	// in the paper, the harsh option is always used
	private static final double MIN_SNAPPED_EDGES_HARSH = 0.8;
	private static final double MIN_SNAPPED_EDGES_RELAXED = 0.5;
	
	private int threadNum;
	private List<Aircraft> aircraft;
	private WeightedMultigraph<TaxiNode,TaxiEdge> graph;
	private List<Integer> indicesToProcess;
	private boolean flightTracksFilesIncludedIntervals;
	
	// for transpositions
	private int maxStepsOut;
	private double stepWidthMetres;
	
	private LatLng[][][] flightpaths;
	private List<RouteTaken>[] aircraftRoutes;
	private String[] flightNames;

	private TaxiGen taxiGen;
	
	/**snapped version of the original flight track; useful to rerun snapping process without so much work*/
	private PrintStream snappedOut;
	
	/**details of how edge times were calculated are written here*/
	private PrintStream timesOut;
	
	// alg params
	private int kForStage2PathReduction;
	
	
	/**
	 * @param TaxiGen is a param so we can get the GMW IDs for edges and nodes
	 * @param startIndex inclusive
	 * @param endIndex exclusive
	 */
	public SnapTracksThread(int threadNum, List<Aircraft> aircraft, List<Integer> indicesToProcess, WeightedMultigraph<TaxiNode,TaxiEdge> graph, LatLng[][][] flightpaths, List<RouteTaken>[] aircraftRoutes, String[] flightNames, boolean flightTracksFilesIncludedIntervals, double stepWidthMetres, int maxStepsOut, TaxiGen at, PrintStream snappedOut, PrintStream timesOut) {
		super("SnapTracksThread" + threadNum);
		this.threadNum = threadNum;
		this.aircraft = aircraft;
		this.graph = graph;
		this.flightpaths = flightpaths;
		this.aircraftRoutes = aircraftRoutes;
		this.flightNames = flightNames;
		this.flightTracksFilesIncludedIntervals = flightTracksFilesIncludedIntervals;
		
		this.stepWidthMetres = stepWidthMetres;
		this.maxStepsOut = maxStepsOut;
		
		this.taxiGen = at;
		this.snappedOut = snappedOut;
		this.timesOut = timesOut;
		
		this.indicesToProcess = indicesToProcess;
		
		this.kForStage2PathReduction = 10;
	}
	
	/**if there are any indices left to process, grab one and process it*/
	public Integer getNextIndex() {
		Integer i = null;
		synchronized (this.indicesToProcess) {
			if (!this.indicesToProcess.isEmpty()) {
				i = this.indicesToProcess.remove(0);
			}
		}
		
		return i;
	}
	
	public void run() {
		Integer currentAircraft;
		while ((currentAircraft = getNextIndex()) != null) {
			printlnSafelyToSystemOut("Snapping route for aircraft " + currentAircraft + " of " + aircraft.size() + ", " + aircraft.get(currentAircraft).getLabel());
			
			// make a copy of the coords so we can play with them safely
			List<TimeCoordinate> orgCoords = aircraft.get(currentAircraft).getCoords();
			List<TimeCoordinate> newCoords = new ArrayList<TimeCoordinate>();
			for (int j = 0; j < orgCoords.size(); j++) {
				newCoords.add(orgCoords.get(j).copyOf());
			}
			
			// try snapoping just the raw coordinates
			SnapTracksThread.RouteTaken routeTaken = snapRouteToGraph(graph, newCoords, true, currentAircraft);
			
			// do we need to transpose the coordinates?
			double lonAdded = 0;
			double latAdded = 0;
			boolean success = false;
			List<TimeCoordinate> transposedCoords = null;
			if (routeTaken.getSnappings().size() > 0) { // if route was successfully snapped...
				success = true; // don't need to do any more!
			} else {
				// if not successfully snapped, it might just need transposed. Two ways to do this:
				// 1. look at the coords at either end for a straight line. This will be the runway. Then coords to fit the true runways
				// 2. (less brittle but more time consuming) walk in a spiral out from the original coords - this is the approach in the paper 
				printlnSafelyToSystemOut("no routes found - trying some variations...");
				boolean done = false;
				
				// get all possible transposed points
				LatLng[] newCoordsLL = new LatLng[newCoords.size()];
				for (int i = 0; i < newCoordsLL.length; i++) {
					newCoordsLL[i] = newCoords.get(i).getCoord();
				}
				LatLng[][] transposed = DisplaceAroundAPoint.coordsAroundPoints(newCoordsLL, maxStepsOut, stepWidthMetres);
				transposedCoords = new ArrayList<TimeCoordinate>(newCoords.size());
				for (int i = 1; !done && (i < transposed.length); i++) { // skip first one as that's the original
					if (i % 50 == 0) {
						printlnSafelyToSystemOut("AC " + currentAircraft + " (" + aircraft.get(currentAircraft).getId() + ") transposing, iteration " + i + "/" + transposed.length);
					}
					
					transposedCoords.clear();
					for (int j = 0; j < transposed[i].length; j++) {
						transposedCoords.add(new TimeCoordinate(transposed[i][j], newCoords.get(j).getTimestamp(), newCoords.get(j).getInterval()));
					}
					
					// try snapping the route on this set of transposed coords
					routeTaken = snapRouteToGraph(graph, transposedCoords, true, currentAircraft); // harsher tolerance for unsnapped edges
					if (routeTaken.getSnappings().size() > 0) {
						latAdded = transposedCoords.get(0).getCoord().getLat() - newCoordsLL[0].getLat();
						lonAdded = transposedCoords.get(0).getCoord().getLng() - newCoordsLL[0].getLng();
						printlnSafelyToSystemOut("AC " + currentAircraft + " (" + aircraft.get(currentAircraft).getId() + ") successfully transposed and snapped, iteration " + i + " adding " + latAdded + " to lat and " + lonAdded + " to lon.");
						success = true;
						done = true;
					}
				}
				
				if (done) {
					printlnSafelyToSystemOut("AC " + currentAircraft + " Now snapped successfully");
				} else {
					printlnSafelyToSystemOut("AC " + currentAircraft + " Still failed to snap successfully");
				}
			} // end of check for successful snap
			
			// if successfully snapped, add to list for visualising, and get stand and runway used by aircraft
			LatLng[][] snappedCoords = new LatLng[2][0]; // needs to be at least two for original and transposed paths
			List<TaxiNode> snappedStandNodes = null;
			List<TaxiNode> snappedRunwayNodes = null;
			List<RouteTaken> splitRoutes = new ArrayList<RouteTaken>();
			double[] forOutput = new double[0]; // used to write out updated data file
			if (success) {
				List<Snapping> snapped = routeTaken.getSnappings();

				double[][] coords = new double[snapped.size() * 2][2];
				for (int j = 0; j < snapped.size(); j++) {
					coords[j*2][1] = snapped.get(j).getSnappedEdge().getTnFrom().getLatCoordinate();
					coords[j*2][0] = snapped.get(j).getSnappedEdge().getTnFrom().getLonCoordinate();
					coords[(j*2)+1][1] = snapped.get(j).getSnappedEdge().getTnTo().getLatCoordinate();
					coords[(j*2)+1][0] = snapped.get(j).getSnappedEdge().getTnTo().getLonCoordinate();
				}
				
				// the snapped route coordinates - just the adjusted ones that met with success - not midpoints, nodes or anything else that might mess things up
				forOutput = new double[orgCoords.size() * 4];
				for (int j = 0; j < orgCoords.size(); j++) {
					if (transposedCoords == null) { // use original coords
						forOutput[j * 4] = orgCoords.get(j).getCoord().getLat(); // no need to swap original back to lat/lon order (it's already in that order), but do need to add altitude for output for consistency
						forOutput[(j * 4) + 1] = orgCoords.get(j).getCoord().getLng();
						forOutput[(j * 4) + 2] = 0;
						forOutput[(j * 4) + 3] = orgCoords.get(j).getInterval();
						
					} else { // use transposed coords
						forOutput[j * 4] = transposedCoords.get(j).getCoord().getLat(); // swap back to lat/lon order and add altitude for output for consistency
						forOutput[(j * 4) + 1] = transposedCoords.get(j).getCoord().getLng();
						forOutput[(j * 4) + 2] = 0;
						forOutput[(j * 4) + 3] = orgCoords.get(j).getInterval();
					}
				}
				
				// now the flightpaths for the KML output
				splitRoutes = splitRoute(routeTaken);
				snappedCoords = new LatLng[splitRoutes.size() + 2][]; // add 2 to leave room for original and transposed paths
				for (int i = 0; i < splitRoutes.size(); i++) {
					List<TaxiNode> splitRouteNodes = snappingListToNodeList(splitRoutes.get(i).getSnappings());
					snappedCoords[i + 2] = nodeListToCoordsList(splitRouteNodes);
				}
				
				// extract visited runways and stands from the lists
				List<TaxiEdge> snappedEdges = SnapTracksThread.snappingListToEdgeList(snapped);
				snappedStandNodes = extractStandNodesFromPath(snappedEdges);
				snappedRunwayNodes = extractRunwayNodesFromPath(snappedEdges);
			}
			
			// even if unsuccessful, add original flight track to output
			snappedCoords[0] = new LatLng[newCoords.size()]; // the original track coords
			for (int i = 0; i < snappedCoords[0].length; i++) {
				snappedCoords[0][i] = newCoords.get(i).getCoord();
			}
			snappedCoords[1] = new LatLng[(transposedCoords!=null)?transposedCoords.size():0]; // the transposed coords
			for (int i = 0; i < snappedCoords[1].length; i++) {
				snappedCoords[1][i] = transposedCoords.get(i).getCoord();
			}
			flightpaths[currentAircraft] = snappedCoords;
			flightNames[currentAircraft] = aircraft.get(currentAircraft).toString();
			aircraftRoutes[currentAircraft] = splitRoutes;
			
			String standNames = "";
			String runwayNames = "";
			if (snappedStandNodes != null) {
				boolean first = true;
				for (TaxiNode tn : snappedStandNodes) {
					standNames += (first?"":",") + tn.getMeta(); // for stands, it's enough to get the stand names
					first = false;
				}
			}
			if (snappedRunwayNodes != null) {
				boolean first = true;
				for (TaxiNode tn : snappedRunwayNodes) {
					runwayNames += (first?"":",") + tn.getId() + "-"+ tn.getMeta(); // for runways, get the entry/exit point too
					first = false;
				}
			}
			
			if (snappedOut != null) {
				long firstTimestamp = aircraft.get(currentAircraft).getCoords().get(0).getTimestamp();
				ArrayTools.roundPlaces = ArrayTools.NOROUNDING;
				printlnSafelyToSnappedOut(aircraft.get(currentAircraft).getId() + SNAPPED_TRACKS_OUTPUT_SEPARATOR + 
						aircraft.get(currentAircraft).getOrigin() + SNAPPED_TRACKS_OUTPUT_SEPARATOR + 
						aircraft.get(currentAircraft).getDestination() + SNAPPED_TRACKS_OUTPUT_SEPARATOR + 
						standNames + SNAPPED_TRACKS_OUTPUT_SEPARATOR + 
						runwayNames + SNAPPED_TRACKS_OUTPUT_SEPARATOR + 
						(success?1:0) + SNAPPED_TRACKS_OUTPUT_SEPARATOR + 
						latAdded + SNAPPED_TRACKS_OUTPUT_SUBSEPARATOR + lonAdded + SNAPPED_TRACKS_OUTPUT_SEPARATOR + 
						ArrayTools.toString(forOutput, SNAPPED_TRACKS_OUTPUT_SUBSEPARATOR) + SNAPPED_TRACKS_OUTPUT_SEPARATOR + 
						firstTimestamp);
			}
		} // end of loop over available aircraft
		
		printlnSafelyToSystemOut("Thread " + this.threadNum + " terminated."); // all done!
	}
	
	
	/**
	 * try snapping a set of coordinates to the taxiway graph
	 * <br/>
	 * in track, coords are pairs as lon,lat
	 * <br/>
	 * look through the initial list of edge candidates, wherever there is a pair of singles, get all direct routes
	 * between them, and compare with the actual edges found. In between the pair, only choose solutions on the route
	 * (but if none of the points features on the route, keep the edge that would represent the shortest trip back to the route)
	 * also - when filling in gaps in the route, do not allow u-turns on runways! (that is r1-r2-r3-r2 where rX is a runway edge)
	 * finally, if we have runways at either end, split at the visited stand in to two routes.
	 * @param if harsh is enabled, snap count must be 80% rather than 50%. this is used once transposing to reduce false positives (and used all the time in the work for the paper)
	 * @return the actual route taken - where u-turns take place, an edge appears more than once
	 */
	public RouteTaken snapRouteToGraph(WeightedMultigraph<TaxiNode, TaxiEdge> graph, List<TimeCoordinate> track, boolean harsh, int aircraftNumberForOutput) {
		boolean localDebug = false; // enable to output KML and debugging data after each step

		// in the comments below "stage" refers to a larger stages in the paper text
		// "step" refers to a step in the algorithm 

		// the default is to return an empty route
		final RouteTaken DEFAULT = new RouteTaken(new ArrayList<Snapping>(), new ArrayList<Boolean>());
		
		double snapDistance = 10; // metres
		double minNumberOfSnappedEdges = harsh ? (MIN_SNAPPED_EDGES_HARSH * track.size()) : (MIN_SNAPPED_EDGES_RELAXED * track.size()); // if more than half the coords are unsnapped, something is very wrong!
		
		if (track.isEmpty()) { // no route? Don't bother.
			return DEFAULT;
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Initial snapping - find all edges within range of each coord");
		}
		
		// stage 1 (steps 4-6): find the set of nearest nodes to each point (ie within 10m)
		List<List<Snapping>> snaps = new ArrayList<List<Snapping>>(track.size());
		for (int i = 0; i < track.size(); i++) {
			List<Snapping> thisCoordSnaps = new ArrayList<Snapping>();
			
			// for every edge, find the nearest point, and if within the snap distance, keep that point and that edge handy
			for (TaxiEdge te : graph.edgeSet()) {
				double[] nearestPointD = TaxiGen.nearestPointOnLine(track.get(i).getCoord().getLat(), track.get(i).getCoord().getLng(), te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate(), te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate());
				LatLng nearestPoint = new LatLng(nearestPointD[0], nearestPointD[1]);
				double distance;
				boolean beyondEndOfEdge = (nearestPoint.getLat() <= Math.min(te.getTnFrom().getLatCoordinate(), te.getTnTo().getLatCoordinate())) || (nearestPoint.getLat() >= Math.max(te.getTnFrom().getLatCoordinate(), te.getTnTo().getLatCoordinate()));
				
				if (beyondEndOfEdge) {
					double distanceFromPointToNode1 = Geography.distance(te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate(), track.get(i).getCoord().getLat(), track.get(i).getCoord().getLng());
					double distanceFromPointToNode2 = Geography.distance(te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate(), track.get(i).getCoord().getLat(), track.get(i).getCoord().getLng());
					
					if (distanceFromPointToNode1 < distanceFromPointToNode2) {
						distance = distanceFromPointToNode1;
						nearestPoint = new LatLng(te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate());
					} else {
						distance = distanceFromPointToNode2;
						nearestPoint = new LatLng(te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate());
					}
				} else {
					distance = Geography.distance(track.get(i).getCoord(), nearestPoint);//TaxiGen.distance(coords[i][1], coords[i][0], nearestPoint[0], nearestPoint[1]);
				}

				if (distance < snapDistance) {
					Snapping s = new Snapping(track.get(i), nearestPoint, distance, te, track.get(i).getTimestamp() * 1000, false);
					thisCoordSnaps.add(s);

					if (localDebug) {
						printlnSafelyToSystemOut(i + "\t" + track.get(i) + "\t" + nearestPoint + "\t" + s);
					}
				}
			}

			if (thisCoordSnaps.size() > 0) {
				snaps.add(thisCoordSnaps);
			}
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Found " + snaps.size() + " snappings, using " + track.size() + " coords, min snapped required " + minNumberOfSnappedEdges);
		}
		
		// step 7
		if (snaps.isEmpty() || snaps.size() < minNumberOfSnappedEdges) { // not enough edges were snapped. give up.
			return DEFAULT;
		}
		
		if (localDebug) {
			debugSnappingsToKML("SnappingStage1", snaps, null);
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Post Stage 1");
			for (int i = 0; i < snaps.size(); i++) {
				List<Snapping> l = snaps.get(i);
				printlnSafelyToSystemOut(i + ","+track.get(i) + ","+ l.size() + ArrayTools.toString(l.toArray()));
			}
		}
		
		// stage 2a (step 8). now, process the edges:
		// at the ends, we should have a single gate or a runway. if there are edges associated with gates, then pick the closest one
		// if there is an edge associated with a runway, pick that. Otherwise, keep all possible edges for now 
		processEndEdges(snaps.get(0));
		processEndEdges(snaps.get(snaps.size() - 1));
		
		if (localDebug) {
			debugSnappingsToKML("SnappingStage2a", snaps, null);
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Post stage 2a (step 8)");
			for (int i = 0; i < snaps.size(); i++) {
				List<Snapping> l = snaps.get(i);
				printlnSafelyToSystemOut(i + ","+track.get(i) + ","+ l.size() + ArrayTools.toString(l.toArray()));
			}
		}
		
		// stage 2b (step 9) - if there are multiple stands being visited between runways, pick the one that we got closest to and drop any others (NB assumes we shouldn't have multiple stands - e.g. towing)
		// likewise, if there is only one stand being visited, make sure it'll be kept by removing any competing edges from it
		// some extra rules:
		// if there's any snappings that have only a stand as a candidate edge ("dedicated snappings"):
		// if only 1 - pick that one
		// then if >1, if one is at the end of the route, pick that
		// then if >1, pick the closest
		// otherwise if no snappings with only 1 stand as a candidate, pick the stand which we came closest to
		// (might be nice to have a vote? how many points near to an edge?)
		Snapping closestStandEdge = null;
		double distanceFromClosestStandEdge = Double.POSITIVE_INFINITY;
		Snapping closestStandEdgeWithDedicatedSnapping = null;
		double distanceFromClosestStandEdgeWithDedicatedSnapping = Double.POSITIVE_INFINITY;
		
		// find them
		Set<Integer> indicesWithStands = new HashSet<Integer>();
		Map<String, Set<Integer>> indicesWithSingleStands = new HashMap<String, Set<Integer>>();

		for (int i = 0; i < snaps.size(); i++) {
			List<Snapping> currentSnaps = snaps.get(i);
			for (Snapping snapping : currentSnaps) {
				if (snapping.getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) {
					indicesWithStands.add(i);
					if (snapping.distanceFromEdge < distanceFromClosestStandEdge) {
						closestStandEdge = snapping;
						distanceFromClosestStandEdge = snapping.distanceFromEdge;
					}
					
					if (currentSnaps.size() == 1) { // this is the only snap for this coordinate
						String standName = snapping.getSnappedEdge().getStandName();
						Set<Integer> indicesForThisStand = indicesWithSingleStands.get(standName);
						if (indicesForThisStand == null) {
							indicesForThisStand = new TreeSet<Integer>();
							indicesWithSingleStands.put(standName, indicesForThisStand);
						}
						indicesForThisStand.add(i);
						
						if (snapping.distanceFromEdge < distanceFromClosestStandEdgeWithDedicatedSnapping) {
							closestStandEdgeWithDedicatedSnapping = snapping;
							distanceFromClosestStandEdgeWithDedicatedSnapping = snapping.distanceFromEdge;
						}
					}
				}
			}
		}
		
		String chosenStandName = null;
		if (indicesWithSingleStands.isEmpty()) { // we didn't find any stands with dedicated snappings. just look for the stand we came closest to.
			// if we didn't come anywhere near a stand, then this isn't a valid path
			if (closestStandEdge == null) {
				return DEFAULT;
			} else {
				chosenStandName = closestStandEdge.getSnappedEdge().getStandName();
			}
		} else if (indicesWithSingleStands.size() == 1) { // so, only one stand has dedicated snappings. Use that one
			chosenStandName = indicesWithSingleStands.keySet().toArray(new String[0])[0];
		} else {
			// is an index at the end of the route? if so, use that
			for (Entry<String, Set<Integer>> e : indicesWithSingleStands.entrySet()) {
				if (e.getValue().contains(0) || e.getValue().contains(snaps.size())) {
					chosenStandName = e.getKey();
				}
			}
			
			// still not found? just pick the dedicated snapping that was closest
			chosenStandName = closestStandEdgeWithDedicatedSnapping.getSnappedEdge().getStandName();
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Closest stand edge:" + closestStandEdge);
			printlnSafelyToSystemOut("Stands with dedicated snappings...");
			for (Entry<String, Set<Integer>> e : indicesWithSingleStands.entrySet()) {
				printlnSafelyToSystemOut(e.getKey() + ", indices: " + ArrayTools.toString(e.getValue().toArray()));
			}
			printlnSafelyToSystemOut("Chosen:" + chosenStandName);
		}

		// remove competitors to closest stand
		for (Integer index : indicesWithStands) {
			List<Snapping> currentSnaps = snaps.get(index.intValue());

			for (int i = currentSnaps.size() - 1; i >= 0; i--) {
				// remove any stand that's not the chosen one
				if ((currentSnaps.get(i).getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) && (!currentSnaps.get(i).getSnappedEdge().getStandName().equals(chosenStandName))) {
					if (localDebug) {
						printlnSafelyToSystemOut("Removing stand " + currentSnaps.get(i).getSnappedEdge().getStandName() + " from snapping list " + index);
					}
					currentSnaps.remove(i);
				}
			}
		}
		// end of stage 2

		if (localDebug) {
			debugSnappingsToKML("SnappingStage2b", snaps, null);
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Post stage 2b (step 9)");
			for (int i = 0; i < snaps.size(); i++) {
				List<Snapping> l = snaps.get(i);
				printlnSafelyToSystemOut(i + ","+track.get(i) + ","+ l.size() + ArrayTools.toString(l.toArray()));
			}
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Starting Stage 3a (step 10)...");
		}
		
		// stage 3a (step 10): work along path, looking for pairs of coords matching single edges (that is, no doubt about which edge a coord corresponds to)
		// get list of k shortest paths between those edges
		// pick the path which maximises the number of edges that would be kept (ie the path contains the most edges from the set of candidate edges between the two coords)
		// then, for any edge set which contains a member of the path, keep that edge (pick closest to the corresponding coord if >1 on path)
		// for any edge sets containing no edges on the path, keep the one that represents the shortest path back to the route
		boolean done = true;
		boolean foundIndex = false;
		int leftIndex = -1;
		for (int i = 0; !foundIndex && i < snaps.size() - 1; i++) { // look for first coord with a single edge candidate, that also precedes one with multiple edge candidates
			if ((snaps.get(i).size() == 1) && (snaps.get(i + 1).size() > 1)) {
				leftIndex = i;
				foundIndex = true;
			}
		}
		done = !foundIndex; // we can now proceed with the search if we found a coord matching 1 edge
		
		while (!done) {
			// find the next coord with single edge, that precedes one with multiple edges, or is at the end
			int rightIndex = -1;
			foundIndex = false;
			for (int i = leftIndex + 1; !foundIndex && i < snaps.size(); i++) {
				if ((snaps.get(i).size() == 1) && ((i == snaps.size() - 1) || (snaps.get(i+1).size() > 1))) {
					rightIndex = i;
					foundIndex = true;
				}
			} // end of second pass through the edges
			
			if (foundIndex) {
				TaxiEdge leftEdge = snaps.get(leftIndex).get(0).getSnappedEdge();
				TaxiEdge rightEdge = snaps.get(rightIndex).get(0).getSnappedEdge();
				
				// if these are the same edge, or adjacent, the path between is somewhat meaningless, so don't bother
				if (!((leftEdge == rightEdge) || leftEdge.isAdjacentTo(rightEdge))) {
					// work out the two closest nodes of the four represented by the edges 
					TaxiNode[] leftRightNodes = getNodePair(graph, leftEdge, rightEdge, true);
					
					if ((leftRightNodes[0] == null) || (leftRightNodes[1] == null)) {
						// if we get here, it's because we found an edge not connected to the rest of the graph
						// issue a warning and continue
						printlnSafelyToSystemOut("Warning: couldn't find a route between edges " + leftEdge.getId() + " and " + rightEdge.getId());
						return DEFAULT;
					}
					
					if (localDebug) {
						printlnSafelyToSystemOut("Looking for paths connects snapping " + leftIndex + " to " + rightIndex + ": " + leftEdge + " >>> " + rightEdge + "; " + leftRightNodes[0] + " >>> " + leftRightNodes[1]);
					}
					
					// get set of paths between the closest nodes
					KShortestPaths<TaxiNode, TaxiEdge> ksp = new KShortestPaths<TaxiNode, TaxiEdge>(graph, leftRightNodes[0], kForStage2PathReduction);
					List<GraphPath<TaxiNode, TaxiEdge>> paths = ksp.getPaths(leftRightNodes[1]);
					
					// for each path, count the number of edges that would be kept if that was the path used
					int maxEdgeCount = 0;
					List<List<Snapping>> reducedEdgeListToKeep = null;
					for (int pathNum = 0; pathNum < paths.size(); pathNum++) {
						int edgeCount = 0;
						List<List<Snapping>> reducedEdges = reduceEdgeListsToPath(snaps, paths.get(pathNum).getEdgeList(), leftIndex, rightIndex);
						
						for (List<Snapping> l : reducedEdges) {
							if (l.size() == 1) { // we count the number of edges remaining. if it's 1, we were able to find one on the path, if it's >1 we couldn't.
								edgeCount++;
							}
						}
						
						if (edgeCount > maxEdgeCount) { // only keep it if it's an improvement (if two are equally good, we want to keep the first one as it's a shorter path)
							reducedEdgeListToKeep = reducedEdges;
							maxEdgeCount = edgeCount;
						}
					}
					
					// now, keep whichever was the most successful reduction
					if (reducedEdgeListToKeep != null) {
						snaps = reducedEdgeListToKeep;
					}
				}
				
				// finally, move along...
				leftIndex = rightIndex;
			} else {
				done = true;
			}
		} // end of stage 3a

		if (localDebug) {
			debugSnappingsToKML("SnappingStage3a", snaps, null);
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Post stage 3a (step 10)");
			for (int i = 0; i < snaps.size(); i++) {
				List<Snapping> l = snaps.get(i);
				printlnSafelyToSystemOut(i + ","+track.get(i) + ","+ l.size() + ArrayTools.toString(l.toArray()));
			}
		}
		
		
		// stage 3b (steps 11-24) - the four loops wrapped up into one
		// there will still be multiple candidates for edges against some time coords. So let's trim some more here.
		for (int i = 0; i < snaps.size(); i++) {
			
			// don't bother if there's 0 or 1 edges for the current time coord
			List<Snapping> currentSnaps = snaps.get(i); 
			if (currentSnaps.size() > 1) {
				Set<Snapping> toKeep = new HashSet<Snapping>();
				
				// multiple edge candidates: could just pick the nearest one, but this
				// has some problems where there are ties. Especially with snapped stuff, where all the distances 
				// are zero, we end up picking an edge that may have nothing to do with the route!
				//
				// so, first we reduce to the set of nearest edges
				// then prioritise Taxiway edges over stands/runways (to try to remove short branches with u-turns appearing on the route)
				// finally, if all else fails just pick one edge at random from those that are nearest
				//
				// we could also refine a bit by either looking further than just the immediate before and after (not done yet)
				
				// step 12 - pick nearest
				double min = Double.POSITIVE_INFINITY;
				Set<Snapping> nearest = new HashSet<Snapping>();
				for (int j = 0; j < snaps.get(i).size(); j++) {
					if (snaps.get(i).get(j).distanceFromEdge < min) {
						nearest.clear();
					}
					
					if (snaps.get(i).get(j).distanceFromEdge <= min) {
						min = snaps.get(i).get(j).distanceFromEdge;
						nearest.add(currentSnaps.get(j));
					}
				}
				toKeep.clear();
				toKeep.addAll(nearest);
				
				// step 16 - still more than one edge for this time coord? pick taxiway in preference to stands/runways
				Set<Snapping> newToKeep = new HashSet<Snapping>();
				if (toKeep.size() > 1) {
					for (Snapping s : toKeep) {
						if (s.getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.TAXIWAY) {
							newToKeep.add(s);
						}
					}
					toKeep = newToKeep;
				}
				
				// step 20 - still more than one edge for this time coord? pick random...
				if (toKeep.size() > 1) {
					Snapping[] arr = toKeep.toArray(new Snapping[toKeep.size()]);
					toKeep.clear();
					int choice = (int)(Math.random() * arr.length);
					toKeep.add(arr[choice]);
					
					if (localDebug) {
						printlnSafelyToSystemOut("Couldn't choose between: " + ArrayTools.toString(arr) + " so picked at random:" + arr[choice]);
					}
				}
								
				// step 23 - now, only keep edges that are marked to be kept
				List<Snapping> newCurrentSnaps = new ArrayList<Snapping>();
				for (int j = 0; j < currentSnaps.size(); j++) {
					if (toKeep.contains(currentSnaps.get(j))) {
						newCurrentSnaps.add(currentSnaps.get(j));
					}
				}

				snaps.set(i, newCurrentSnaps);
			} // end of check for only >1 edges to snap to
		} // end of second pass through the edges
		// end of stage 3b

		if (localDebug) {
			debugSnappingsToKML("SnappingStage3b", snaps, null);
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Post stage 3b (steps 11-24)");
			for (int i = 0; i < snaps.size(); i++) {
				List<Snapping> l = snaps.get(i);
				printlnSafelyToSystemOut(i + ","+track.get(i) + ","+ l.size() + ArrayTools.toString(l.toArray()));
			}
		}
		
		// TODO stage 4a: extend the ends to match likely termini of the route.
		// at the ends, just see whether the last edge was connected to a runway node or a gate node. if it was, stop.
		// if it wasn't, explore the neighbourhood of the last edge to find a runway node or gate node in the shortest distance.

		// NB - by now, there should only be 0 or 1 edges for all points
		// we'll do a little error checking to avoid doing the remining stages unless they're going to produce a valid route
		
		// if there are no snapped edges at all, then give up
		if (snaps.isEmpty()) {
			//printlnSafelyToSystemOut("No snapped edges");
			return DEFAULT;
		}
		
		// check the ends to see if we found valid termini for the route
		if ((snaps.get(0).size() == 0) || snaps.get(snaps.size() - 1).size() == 0) {
			// we get here because the route finishes too far from a runway or stand to be sure where it will end up.
			// Now - if it's a stopover there may be a valid route still, in which case we can just trim the bad bit
			// So, remove everything from the zero-length end to the first occurrence of a runway or stand connection
			// If what's left has a runway and stands then we keep it and proceed 
			// otherwise the whole route is dropped (we return an empty list of coords)
			if (snaps.get(0).size() == 0) {
				done = false;
				while (!done && !snaps.isEmpty()) {
					if ((snaps.get(0).size() == 0) || (snaps.get(0).get(0).getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.TAXIWAY)) {
						snaps.remove(0);
					} else {
						done = true;
					}
				}
			}
			if (!snaps.isEmpty() && snaps.get(snaps.size() - 1).size() == 0) {
				done = false;
				while (!done && !snaps.isEmpty()) {
					if ((snaps.get(snaps.size() - 1).size() == 0) || (snaps.get(snaps.size() - 1).get(0).getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.TAXIWAY)) {
						snaps.remove(snaps.size() - 1);
					} else {
						done = true;
					}
				}
			}
		}
		
		// is this a valid route? Do we have both runways and stands?
		boolean hasRunway = false;
		boolean hasStand = false;
		boolean first = true;
		boolean lastOneHadARunwayCrossing = false;
		for (int i = 0; i < snaps.size(); i++) {
			List<Snapping> edges = snaps.get(i);
			if (edges.size() > 0) {
				if (edges.get(0).getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.RUNWAY) {
					hasRunway = true;
				}
				if (edges.get(0).getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) {
					hasStand = true;
				}
				
				// if first or last edge in route reaches a runway crossing, that'll do (maybe there isn't any further runway movement)
				if ((edges.get(0).getSnappedEdge().getTnFrom().getNodeType() == NodeType.RUNWAY_CROSSING) || (edges.get(0).getSnappedEdge().getTnTo().getNodeType() == NodeType.RUNWAY_CROSSING)) {
					if (first) {
						hasRunway = true;
					}
					lastOneHadARunwayCrossing = true;
				} else {
					lastOneHadARunwayCrossing = false;
				}
				
				first = false;
			}
		}
		if (lastOneHadARunwayCrossing) {
			hasRunway = true;
		}
		
		if (!hasRunway || !hasStand) {
			return DEFAULT;
		}
		
		// stage 4 (step 26) - derive a route by looping over all the edges in the path and interpolating the shortest route between them
		List<Snapping> snappedRoute = new ArrayList<Snapping>();
		Snapping previousSnap = snaps.get(0).get(0);
		snappedRoute.add(previousSnap); // always add the first edge!
		for (int j = 0; j < snaps.size(); j++) {
			if (!snaps.get(j).isEmpty()) { // skip points where we didn't get a matching edge
				Snapping currentSnap = snaps.get(j).get(0);
				
				// if not on first edge, and not on a repeat of the previous edge, add any intermediate edges between the previous and the current
				// NB - we want the shortest path so we need to do this from all four pairs of nodes (this ought to be improved sometime)
				// avoid U-turns here somehow? not needed - branches trimmed out later.
				if (j > 0) { // not on first edge
					if (currentSnap.getSnappedEdge() != previousSnap.getSnappedEdge()) { // not a duplicate of previous edge
						if (!currentSnap.getSnappedEdge().isAdjacentTo(previousSnap.getSnappedEdge())) { // not already an adjacent edge to previous
							DijkstraShortestPath<TaxiNode, TaxiEdge> dsp = new DijkstraShortestPath<TaxiNode, TaxiEdge>(graph, previousSnap.getSnappedEdge().getTnFrom(), currentSnap.getSnappedEdge().getTnFrom());
							List<TaxiEdge> shortestPath = dsp.getPathEdgeList();
							double shortest = dsp.getPathLength();
							boolean previousFromTo = false; // keeps track of which nodes were closest together (these are the direction travelled on the previous and current edges). 
							boolean currentFromTo = true; // Here prevFrom and curFrom were closest, to prev was backwards (FromTo==false), and cur was forwards (FromTo==true)
							
							dsp = new DijkstraShortestPath<TaxiNode, TaxiEdge>(graph, previousSnap.getSnappedEdge().getTnFrom(), currentSnap.getSnappedEdge().getTnTo());
							double length = dsp.getPathLength();
							if (length < shortest) {
								shortestPath = dsp.getPathEdgeList();
								shortest = length;
								previousFromTo = false; // Here prevFrom and curTo were closest, to prev was backwards (FromTo==false), and cur was backwards (FromTo==false)
								currentFromTo = false;
							}
							dsp = new DijkstraShortestPath<TaxiNode, TaxiEdge>(graph, previousSnap.getSnappedEdge().getTnTo(), currentSnap.getSnappedEdge().getTnFrom());
							length = dsp.getPathLength();
							if (length < shortest) {
								shortestPath = dsp.getPathEdgeList();
								shortest = length;
								previousFromTo = true; // Here prevTo and curFrom were closest, to prev was forwards (FromTo==true), and cur was forwards (FromTo==true)
								currentFromTo = true;
							}
							dsp = new DijkstraShortestPath<TaxiNode, TaxiEdge>(graph, previousSnap.getSnappedEdge().getTnTo(), currentSnap.getSnappedEdge().getTnTo());
							length = dsp.getPathLength();
							if (length < shortest) {
								shortestPath = dsp.getPathEdgeList();
								shortest = length;
								previousFromTo = true; // Here prevTo and curTo were closest, to prev was forwards (FromTo==true), and cur was backwards (FromTo==false)
								currentFromTo = false;
							}
							
							if (localDebug) {
								System.out.println("Completing path between " + previousSnap.getSnappedEdge() + " and " + currentSnap.getSnappedEdge() + ":" + shortestPath.size() + " edges, " + previousFromTo + "/" + currentFromTo);
							}
							
							// also estimate arrival times at these edges, simply chop up according to total length of path, and start/end times, but accounting for distance travelled on previous+current edges as the timestamp probably doesn't correspond with the middle of the edge
							Snapping.CoordTime lastTimeAtPreviousEdge = previousSnap.getLatestTimeAtCoord();
							Snapping.CoordTime firstTimeAtCurrentEdge = currentSnap.getEarliestTimeAtCoord();
							double distanceOnPreviousEdge = previousSnap.getSnappedEdge().getLength() * (previousFromTo ? lastTimeAtPreviousEdge.getFractionAlongEdgeForTime() : 1 - lastTimeAtPreviousEdge.getFractionAlongEdgeForTime());
							double distanceOnCurrentEdge = currentSnap.getSnappedEdge().getLength() * (currentFromTo ? firstTimeAtCurrentEdge.getFractionAlongEdgeForTime() : 1 - firstTimeAtCurrentEdge.getFractionAlongEdgeForTime());
							long[] times = estimateInterpolatedEdgeTimes(previousSnap.getSnappedEdge(), currentSnap.getSnappedEdge(), shortestPath, lastTimeAtPreviousEdge.getTimeAtCoord(), firstTimeAtCurrentEdge.getTimeAtCoord(), distanceOnPreviousEdge, distanceOnCurrentEdge, localDebug);
							
							for (int i = 0; i < shortestPath.size(); i++) {
								TaxiEdge te = shortestPath.get(i);
								// ignore current and previous edges if they appeared in there
								if ((te != currentSnap.getSnappedEdge()) && (te != previousSnap.getSnappedEdge())) {
									Snapping s = new Snapping(te, times[i], true);
									snappedRoute.add(s);
								}
							}
						}
					} else { // this is a duplicate of the previous edge. Before we drop it, save the time associate with this snapping's original coordinate
						Snapping keptSnapping = snappedRoute.get(snappedRoute.size() - 1);
						keptSnapping.addCoordTimes(currentSnap.getTimesAtCoord());
					}
				}
				
				// add the current edge
				if (currentSnap.getSnappedEdge() != previousSnap.getSnappedEdge()) {
					snappedRoute.add(currentSnap);
				}
				
				previousSnap = currentSnap;
			}
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("After interpolation (stage 4), before end tidying");
			printlnSafelyToSystemOut(ArrayTools.toString(snappedRoute.toArray(), "\t"));
		}
		
		if (localDebug) {
			debugSnappingsToKML("SnappingStage4", null, snappedRoute);
		}
		
		// tidy up any loose ends 
		trimRouteEnds(snappedRoute);
		
		if (localDebug) {
			debugSnappingsToKML("SnappingStage4.1", null, snappedRoute);
		}
		
		// now convert to a route taken - figure out what order the edges are traversed. 
		List<Snapping> snappingsBetweenNodes = new ArrayList<Snapping>(); // this is different to route; it will have duplicates and nulls as it represents the actual edges travelled over in the order they were travelled. However, any edges here will be in routeEdges, in the same order
		List<Boolean> snappingsBetweenNodesDirection = new ArrayList<Boolean>();
		snappingListToNodeList(snappedRoute, snappingsBetweenNodes, snappingsBetweenNodesDirection); // don't care about the node list that this method generates
		// by this point there should be no gaps in the route, so flag an error if there are
		if (snappingsBetweenNodes.contains(null)) {
			printlnSafelyToSystemOut("disconnection found in route!");
		}
		RouteTaken rval = new RouteTaken(snappingsBetweenNodes, snappingsBetweenNodesDirection);
		
		if (localDebug) {
			printlnSafelyToSystemOut("Pre stage 5");
			printlnSafelyToSystemOut(ArrayTools.toString(rval.getSnappings().toArray(), "\t"));
		}
		
		if (localDebug) {
			debugSnappingsToKML("SnappingPreStage5", null, rval.getSnappings());
		}
		
		// stage 5 (step 26):
		// trim out any niggling little branches that are basically noise. this is counted as any parts of the route with a 180
		// degree turn, where the 180 doesn't take place on a stand connection, or "next to" a stand connection
		// ("next to" means a reverse-out from a stand - this is detected by working out from the 180 turn along the duplicated
		// legs of the route. if the route duplication ends at a stand connection, that's fine. if not, then the branch is dropped
		// in its entirety)
		trimRouteBranches(rval, localDebug);
		
		if (localDebug) {
			debugSnappingsToKML("SnappingStage5", null, rval.snappings);
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Post stage 5 (step 26)");
			printlnSafelyToSystemOut(ArrayTools.toString(rval.getSnappings().toArray(), "\t"));
		}

		// determine runways used along route - currently unused because it gets tricky to carry the right value over after splitting the tracks later
		List<String> runwaysUsed = determineRunwaysUsedByRoute(rval.snappings, rval.snappingsFromTo);
		rval.setRunwaysUsed(runwaysUsed);
		
		// stage 6 (steps 27-29) - calc times now to get runway times too
		rval.setTimesTaken(flightTracksFilesIncludedIntervals ? calcTimesToTravelEdges(rval, aircraftNumberForOutput) : new ArrayList<EdgeTime>(Arrays.asList(new EdgeTime[rval.getSnappings().size()])));
		
		if (localDebug) {
			printlnSafelyToSystemOut("Post stage 6 (steps 27-29)");
			StringBuffer buf = new StringBuffer();
			for (int i = 0; i < rval.getSnappings().size(); i++) {
				buf.append(rval.getSnappings().get(i).getSnappedEdge() + "\t" + rval.getRunwaysUsed().get(i) + System.lineSeparator());
			}
			buf.append("First runway: " + rval.getFirstRunwayID() + ", Last runway: " + rval.getLastRunwayID() + ", Actual runway:" + rval.getActualRunwayUsed() + System.lineSeparator());
			printlnSafelyToSystemOut(buf.toString());
			debugSnappingsToKML("SnappingStage6", null, rval.snappings, true, rval.snappings.get(0).getEarliestTimeAtCoord().timeAtCoord, rval.getTimesTaken());
		}
		
		// stage 7a (step 30) - drop runway edges now we have the whole taxi route
		// stage 7b (step 31) is done after this returns
		for (int i = rval.snappings.size() - 1; i >= 0; i--) {
			if (rval.snappings.get(i).getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.RUNWAY) {
				rval.removeSnappingAtPosition(i);
			}
		}
		
		if (localDebug) {
			debugSnappingsToKML("SnappingStage7a", null, rval.snappings, true, rval.snappings.get(0).getEarliestTimeAtCoord().timeAtCoord, rval.getTimesTaken());
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Post stage 7a (step 30)");
			printlnSafelyToSystemOut(ArrayTools.toString(rval.getSnappings().toArray(), "\t"));
		}
		
		return rval;
	}
	
	/**
	 * Sometimes due to snapping errors we have a track that doesn't start/end on a stand or runway.
	 * This trims the end edges so that the route does
	 * really, this method shouldn't need to do anything as we now reduce the ends to a stand and runway already
	 */
	private void trimRouteEnds(List<Snapping> routeTaken) {
		List<Integer> toRemoveFromBeginning = new ArrayList<Integer>();
		boolean done = false;
		for (int i = 0; !done && (i < routeTaken.size()); i++) {
			TaxiEdge te = routeTaken.get(i).getSnappedEdge();
			if (te.getEdgeType() == TaxiEdge.EdgeType.RUNWAY || (te.getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION)) { // actually look for type "runway" or "stand connection" - it's not good enough to look for a runway crossing because a route starting on a taxiway that then enters a runway is a departure, possibly followed by an arrival of the same aircraft. It'll have some points on the runway itself to detect, and if we just trim to the runway crossing edge, then we end up with a bit of the departure just before the arrival, and ultimately a broken route. 
				done = true;
			} else {
				toRemoveFromBeginning.add(i);
			}
		}
		
		List<Integer> toRemoveFromEnd = new ArrayList<Integer>();
		done = false;
		for (int i = routeTaken.size() - 1; !done && (i >= 0); i--) {
			TaxiEdge te = routeTaken.get(i).getSnappedEdge();
			if (te.getEdgeType() == TaxiEdge.EdgeType.RUNWAY || (te.getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION)) {
				done = true;
			} else {
				toRemoveFromEnd.add(i);
			}
		}
		
		// really, this method shouldn't need to do anything as we now reduce the ends to a stand and runway already
		// so flag up an error if we get here.
		if (!toRemoveFromBeginning.isEmpty() || !toRemoveFromEnd.isEmpty()) {
			printlnSafelyToSystemOut("Needing to remove " + toRemoveFromBeginning.size() + " from start and " + toRemoveFromEnd.size() + " from end");
		}
		
		for (int i = 0; i < toRemoveFromEnd.size(); i++) {
			routeTaken.remove(toRemoveFromEnd.get(i).intValue());
		}
		
		for (int i = toRemoveFromBeginning.size() - 1; i >= 0; i--) {
			routeTaken.remove(toRemoveFromBeginning.get(i).intValue());
		}
	}
	
	/**
	 * stage 5 (step 26). trim out any niggling little branches that are basically noise. this is counted as any parts of the route with a 180
	 * degree turn, where the 180 doesn't take place on a stand connection, or "next to" a stand connection
	 * ("next to" means a reverse-out from a stand - this is detected by working out from the 180 turn along the duplicated
	 * legs of the route. if the route duplication ends at a stand connection, that's fine. if not, then the branch is dropped
	 * in its entirety)
	 */
	private void trimRouteBranches(RouteTaken routeTaken, boolean localDebug) {
		List<Snapping> snappingsBetweenNodes = routeTaken.getSnappings();
		
		// get edge list representing route
		Set<Integer> toRemoveIndices = new HashSet<Integer>();
		
		// now, iterate over the list of nodes. wherever we find a pair of edges, that's a 180 turn, so start the process
		Snapping previousSnapping = null;
		int index = -1;
		int previousIndex = -1;
		do {
			previousSnapping = snappingsBetweenNodes.get(++index);
			previousIndex = index;
		} while ((previousSnapping == null) && (index < snappingsBetweenNodes.size() - 1));
		
		while (index < snappingsBetweenNodes.size() - 1) { // step through each edge in route
			// skip nulls!
			Snapping currentSnapping = null;
			do {
				currentSnapping = snappingsBetweenNodes.get(++index);
			} while ((currentSnapping == null) && (index < snappingsBetweenNodes.size()));
			
			// if we find an edge duplicated
			if (previousSnapping.getSnappedEdge() == currentSnapping.getSnappedEdge()) {
				// work back and forward at same time
				int backIndex = previousIndex;
				int forwardIndex = index;
				Snapping back = previousSnapping;
				Snapping forward = currentSnapping;
				boolean foundRunwayOrStand = false;
				Set<Integer> localToRemoveIndices = new HashSet<Integer>();

				boolean done = false;
				do {
					// if we've reached a divergence, or a null (path incomplete), then we're done
					if ((back == null) || (back.getSnappedEdge() != forward.getSnappedEdge())) {
						// check that this isn't a branch next to a stand or runway (where reversing might really happen)
						// note - if the branch is simply leaving the stand/runway and returning to it (back==forward) then we still want to drop it
						if (((back != null) && isSnappingNearStandOrRunway(snappingsBetweenNodes, backIndex, false) /*(back.getSnappedEdge().containsRunwayOrRunwayConnectionNode() || back.getSnappedEdge().getType() == Type.STAND_CONNECTION)*/) || ((forward != null) && isSnappingNearStandOrRunway(snappingsBetweenNodes, forwardIndex, true) /*(forward.getSnappedEdge().containsRunwayOrRunwayConnectionNode() || forward.getSnappedEdge().getType() == Type.STAND_CONNECTION)*/)) {
							foundRunwayOrStand = true;
						}
						
						done = true;
					} else {
						// don't bother if the branch terminates in a runway/stand either
						if ((back != null) && (back.getSnappedEdge().containsRunwayOrRunwayConnectionNode() || back.getSnappedEdge().getEdgeType() == EdgeType.STAND_CONNECTION)) {
							foundRunwayOrStand = true;
							done = true;
						} else {
							localToRemoveIndices.add(backIndex);
							localToRemoveIndices.add(forwardIndex);
						}
						
						//back = snappingsBetweenNodes.get(--backIndex); // can't just do this, we might end up trying to remove an edge already removed, which introduces a break in the path when its partner is removed
						forward = (++forwardIndex < (snappingsBetweenNodes.size())) ? snappingsBetweenNodes.get(forwardIndex) : null;
						
						// skip back until we find an edge that's not already removed
						while (toRemoveIndices.contains(--backIndex)) {
							/*do nothing except decrement*/
						}
						back = (backIndex >= 0) ? snappingsBetweenNodes.get(backIndex) : null;
					}
				} while ((backIndex >= 0) && (forwardIndex < snappingsBetweenNodes.size()) && !done && !foundRunwayOrStand); // keep going unless at either end of list, or if we reach a pair of non-matching edges, or if we've found a stand/runway
				
				if (!foundRunwayOrStand) { // this branch is eligible for deletion
					if (localDebug) {
						printlnSafelyToSystemOut("ADDING:" + ArrayTools.toString(localToRemoveIndices.toArray()));
					}
					toRemoveIndices.addAll(localToRemoveIndices);
					
					// we also need to skip on past the branch. Move "next" to the immediate neighbour after the branch
					index = forwardIndex;
					currentSnapping = forward;
				}
			}

			// prepare for move on to next pair for comparison
			previousSnapping = currentSnapping;
			previousIndex = index;
		}
		
		// finally, remove any snappings present in branches that were found
		int[] toRemove = new int[toRemoveIndices.size()];
		int i = 0;
		for (Integer j : toRemoveIndices) {
			toRemove[i++] = j;
		}
		Arrays.sort(toRemove);
		
		for (int j = toRemove.length - 1; j >= 0; j--) { // do this backwards so that later indices are still valid after previous ones are removed
			int indexToRemove = toRemove[j];
			routeTaken.removeSnappingAtPosition(indexToRemove);
		}
	}
	
	/**
	 * is the given index in a route close to a stand? (this is for working out whether a branch starts near to a stand, which would mean that it's a reverse associated with pushback)
	 * used to just check edge immediately adjacent to the branch, but thanks to curves etc sometimes this wasn't quite enough
	 */
	private static boolean isSnappingNearStandOrRunway(List<Snapping> snappings, int index, boolean forwards) {
		double thresholdDistanceMetres = 30;
		
		double currentDistance = 0;
		boolean found = false;
		for (int curPos = index; !found && (curPos >= 0) && (curPos < snappings.size()) && (currentDistance < thresholdDistanceMetres); curPos += (forwards ? 1 : -1)) {
			currentDistance += snappings.get(curPos).getSnappedEdge().getLength();
			
			if ((snappings.get(curPos).getSnappedEdge().getEdgeType() == EdgeType.STAND_CONNECTION) || snappings.get(curPos).getSnappedEdge().containsRunwayOrRunwayConnectionNode()) {
				found = true;
			}
		}
		
		return found;
	}
	
	/**
	 * stage 2 (step 8) - take the first and last sets of edge candidates in a track, and try to reduce to one definitive edge
	 */
	private static void processEndEdges(List<Snapping> snaps) {
		List<Snapping> newSnaps = new ArrayList<Snapping>();
		
		// first, look for edges connected to runways/stands nearby and if any are found, take the closest one
		double minDistanceToStand = Double.POSITIVE_INFINITY;
		double minDistanceToRunway = Double.POSITIVE_INFINITY;
		int closestStandEdge = -1;
		int closestRunwayEdge = -1;
		for (int i = 0; i < snaps.size(); i++) {
			// check edge type for stands, because there may be multiple edges before reaching the stand
			// check for node types on runway, because we can't include edges for the runway in the graph
			if (snaps.get(i).getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) { 
				if (snaps.get(i).distanceFromEdge < minDistanceToStand) {
					minDistanceToStand = snaps.get(i).distanceFromEdge;
					closestStandEdge = i;
				}
			}
			
			// keep all nodes associated with a runway
			if ((snaps.get(i).getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.RUNWAY) || (snaps.get(i).getSnappedEdge().getTnFrom().getNodeType() == NodeType.RUNWAY_CROSSING) || (snaps.get(i).getSnappedEdge().getTnTo().getNodeType() == NodeType.RUNWAY_CROSSING)) {			
				if (snaps.get(i).distanceFromEdge < minDistanceToRunway) {
					minDistanceToRunway = snaps.get(i).distanceFromEdge;
					closestRunwayEdge = i;
				}
			}
		}
		
		if (closestStandEdge >= 0) {
			newSnaps.add(snaps.get(closestStandEdge));
		}
		
		if (closestRunwayEdge >= 0) {
			newSnaps.add(snaps.get(closestRunwayEdge));
		}
		
		// only replace original arrays if we found stands / runways
		if (!newSnaps.isEmpty()) {
			snaps.clear();
			snaps.addAll(newSnaps);
		}
	}
	
	/**
	 * @return the pair of nodes belonging to the specified edges that are nearest or furthest apart.
	 * These will be nulls if no connection could be found!
	 */
	public static TaxiNode[] getNodePair(WeightedMultigraph<TaxiNode, TaxiEdge> graph, TaxiEdge leftEdge, TaxiEdge rightEdge, boolean nearest) {
		double best = nearest ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
		TaxiNode leftNode = null;
		TaxiNode rightNode = null;
		
		DijkstraShortestPath<TaxiNode, TaxiEdge> dsp = new DijkstraShortestPath<TaxiNode, TaxiEdge>(graph, leftEdge.getTnFrom(), rightEdge.getTnFrom());
		double length = dsp.getPathLength();
		if (nearest ? length < best : length > best) {
			leftNode = leftEdge.getTnFrom();
			rightNode = rightEdge.getTnFrom();
			best = length;
		}
		
		dsp = new DijkstraShortestPath<TaxiNode, TaxiEdge>(graph, leftEdge.getTnFrom(), rightEdge.getTnTo());
		length = dsp.getPathLength();
		if (nearest ? length < best : length > best) {
			leftNode = leftEdge.getTnFrom();
			rightNode = rightEdge.getTnTo();
			best = length;
		}
		
		dsp = new DijkstraShortestPath<TaxiNode, TaxiEdge>(graph, leftEdge.getTnTo(), rightEdge.getTnFrom());
		length = dsp.getPathLength();
		if (nearest ? length < best : length > best) {
			leftNode = leftEdge.getTnTo();
			rightNode = rightEdge.getTnFrom();
			best = length;
		}
		
		dsp = new DijkstraShortestPath<TaxiNode, TaxiEdge>(graph, leftEdge.getTnTo(), rightEdge.getTnTo());
		length = dsp.getPathLength();
		if (nearest ? length < best : length > best) {
			leftNode = leftEdge.getTnTo();
			rightNode = rightEdge.getTnTo();
			best = length;
		}
		
		return new TaxiNode[] {leftNode, rightNode};
	}
	
	/**
	 * go through the sets of possible edges, and keep the ones that correspond to the path
	 * (if any of the lists doesn't have any edge that appears on the path, keep all of them in that list)
	 * match the ordering on the path - so once we've moved into the next edge on the path, ones preceding it won't be kept (to reduce dead ends)
	 * works between left/rightIndex exclusive; edge lists outwith that are just copied as-is
	 */
	private List<List<Snapping>> reduceEdgeListsToPath(List<List<Snapping>> snaps, List<TaxiEdge> path, int leftIndex, int rightIndex) {
		List<List<Snapping>> reducedPath = new ArrayList<List<Snapping>>(snaps.size());
		
		for (int i = 0; i <= leftIndex; i++) {
			reducedPath.add(snaps.get(i));
		}
		
		for (int i = leftIndex + 1; i < rightIndex; i++) {
			List<Snapping> candidateEdges = snaps.get(i);
			List<Snapping> reducedEdges = new ArrayList<Snapping>(candidateEdges.size());
			
			// do any of the edges at the current position feature in what's left of the path?
			// if so, keep the one that appears first in the path
			Snapping toKeep = null;
			int positionInPathOfEdgeToKeep = path.size();
			for (Snapping s : candidateEdges) {
				int position = path.indexOf(s.getSnappedEdge());
				if ((position > -1) && (position < positionInPathOfEdgeToKeep)) { // if edge is in the path, and is earlier than any others in the path
					toKeep = s;
					positionInPathOfEdgeToKeep = position;
				}
			}
			
			// if we found an edge in the path, use that, otherwise just keep all the candidates
			if (toKeep != null) {
				reducedEdges.add(toKeep);
				
				// trim path if appropriate
				path = path.subList(positionInPathOfEdgeToKeep, path.size());
			} else {
				reducedEdges.addAll(candidateEdges);
			}
			
			reducedPath.add(reducedEdges);
		}
		
		for (int i = rightIndex; i < snaps.size(); i++) {
			reducedPath.add(snaps.get(i));
		}
		
		return reducedPath;
	}
	
	/** 
	 * given a list of edges representing a path, and a start time and end time, figure out time of arrival
	 * for all the edges on the path. Assumes that time of arrival is at the midpoint of the edge
	 * start and end edges are given so if they're present in the path they can be flagged up (they shouldn't be in the interpolated path at all)
	 * travelledOnEdge params are the distance travelled on these edges, which is factored in when spreading the timestamps out
	 */
	public long[] estimateInterpolatedEdgeTimes(TaxiEdge startEdge, TaxiEdge endEdge, List<TaxiEdge> shortestPath, long startTime, long endTime, double travelledOnStartEdge, double travelledOnEndEdge, boolean localDebug) {
		long[] times = new long[shortestPath.size()];
		if (shortestPath.isEmpty()) {
			return times;
		}
		
		long totalTime = endTime - startTime;
		double remainingOnStartEdge = startEdge.getLength() - travelledOnStartEdge; // this is what matters here
		
		// get length of path
		double totalLength = remainingOnStartEdge + travelledOnEndEdge;
		for (int i = 0; i < shortestPath.size(); i++) {
			TaxiEdge edge = shortestPath.get(i);
			if ((edge != startEdge) && (edge != endEdge)) {
				totalLength += edge.getLength();
			}
		}
		
		long timeTakenOnStartEdge = (long)(totalTime * (remainingOnStartEdge / totalLength));

		if (localDebug) {
			printlnSafelyToSystemOut("Interpolating times between " + startEdge.getId() + " and " + endEdge.getId() + ": lengths:" + startEdge.getLength() + "/" + endEdge.getLength() + ", remOnStart/toGoOnEnd:" + remainingOnStartEdge + "/" + travelledOnEndEdge + " tOnStart:" + timeTakenOnStartEdge + " totalTime:" + totalTime + " totalLength:" + totalLength);
		}
		
		// assign times
		long currentTime = startTime + timeTakenOnStartEdge;
		for (int i = 0; i < shortestPath.size(); i++) {
			TaxiEdge edge = shortestPath.get(i);
			if (edge == startEdge) {
				times[i] = startTime;
				System.err.println("Start edge found in interpolated path:" + edge + " found between " + startEdge + " and " + endEdge);
			} else if (edge == endEdge) {
				times[i] = endTime;
				System.err.println("End edge found in interpolated path:" + edge + " found between " + startEdge + " and " + endEdge);
			} else {
				long timeForThisEdge = (long)(totalTime * (edge.getLength() / totalLength));
				if (localDebug) System.out.println("NOW ADDING TIME FOR EDGE " + edge.getId() + ":" + currentTime + " - " + timeForThisEdge + "/" + totalTime + " tot:" + totalLength + ", this:" + edge.getLength());
				times[i] = (long)(currentTime + (0.5 * timeForThisEdge)); // *0.5 because it's the time halfway along the edge
				currentTime += timeForThisEdge; // now add on the whole time for the edge
			}
		}
		
		return times;
	}
	
	public static List<TaxiEdge> snappingListToEdgeList(List<Snapping> s) {
		List<TaxiEdge> rval = new ArrayList<TaxiEdge>(s.size());
		
		for (int i = 0; i < s.size(); i++) {
			rval.add(s.get(i).getSnappedEdge());
		}
		
		return rval;
	}
	
	public static List<TaxiNode> snappingListToNodeList(List<Snapping> edges) {
		return snappingListToNodeList(edges, null, null);
	}
	
	/**
	 * Works best if edges in list are all connected, otherwise draws shortest line between disconnected edges
	 * This also updates the Snapping objects with a direction travelled
	 * @param edges the route to convert
	 * @param edgesBetweenNodes - into this list will be put all the edges corresponding to nodes (for use in path trimming in SnapTracks class)
	 *     <br>--- this list will be 1 element shorter than the element list, and represents the edge between each node, starting with nodes 0,1
	 *     <br>--- if there wasn't an edge between a pair of nodes, a null is present
	 *     <br>--- as we go through this, whenever we add a node to the list of nodes (except the first) we add an edge to the list of edges too
	 * @param edgesBetweenNodesFromTo - list corresponding to edgesBetweenNodes. If element is true, edge was travelled fromNode->toNode, if false, it was travelled toNode->fromNode (for code-brevity we assume that if edgesBetweenNodes is non-null, this is too)
	 * @return list of all nodes visited by a route marked by an edge list.*/
	public static List<TaxiNode> snappingListToNodeList(List<SnapTracksThread.Snapping> edges, List<SnapTracksThread.Snapping> edgesBetweenNodes, List<Boolean> edgesBetweenNodesFromTo) {
		if (edges.size() == 1) {
			if (edgesBetweenNodes != null) {
				edgesBetweenNodes.add(edges.get(0));
				edgesBetweenNodesFromTo.add(true);
			}
			
			return Arrays.asList(new TaxiNode[]{edges.get(0).getSnappedEdge().getTnFrom(), edges.get(0).getSnappedEdge().getTnTo()});
		}
		
		List<TaxiNode> nodes = new ArrayList<TaxiNode>();
		boolean previousFromTo;
		
		// first, find the start node - the one which isn't present in the second edge.
		// for each edge, look at the two nodes, and work out which was present in the previous node. Add the other node and move on.
		TaxiNode previousNode;
		Snapping previousSnapping = edges.get(0);
		TaxiEdge previousEdge = previousSnapping.getSnappedEdge();
		if ((previousEdge.getTnFrom() != edges.get(1).getSnappedEdge().getTnFrom()) && (previousEdge.getTnFrom() != edges.get(1).getSnappedEdge().getTnTo())) {
			previousNode = previousEdge.getTnFrom();
			previousFromTo = true;
		} else {
			previousNode = previousEdge.getTnTo();
			previousFromTo = false;
		}
		nodes.add(previousNode);

		for (int i = 0; i < edges.size(); i++) {
			Snapping currentSnapping = edges.get(i);
			TaxiEdge currentEdge = currentSnapping.getSnappedEdge();
			boolean currentFromTo;
			
			TaxiNode currentNode;
			if (currentEdge.isAdjacentTo(previousEdge) && !currentEdge.containsNode(previousNode)) { // have we doubled back? edges are adjacent, but previous node isn't the one joining to the current node
				// in this case, add both nodes of the current edge.
				// which node on the current edge connects to the previous edge?
				if (previousEdge.containsNode(currentEdge.getTnFrom())) { // the from-node
					previousNode = currentEdge.getTnFrom();
					currentNode = currentEdge.getTnTo();
					currentFromTo = true;
				} else { // the to-node
					previousNode = currentEdge.getTnTo();
					currentNode = currentEdge.getTnFrom();
					currentFromTo = false;
				}
				
				nodes.add(previousNode);
				if (edgesBetweenNodes != null) {
					edgesBetweenNodes.add(previousSnapping);
					edgesBetweenNodesFromTo.add(!previousFromTo); // add a reversed-direction move along the previous edge to correspond with the double-back
				}
			} else if (currentEdge.getTnFrom() == previousNode) { // the current edge's from-node is shared with the previous edge
				currentNode = currentEdge.getTnTo();
				currentFromTo = true;
			} else if (currentEdge.getTnTo() == previousNode) { // the current edge's to-node is shared with the previous edge
				currentNode = currentEdge.getTnFrom();
				currentFromTo = false;
			} else {
				// neither of the current edge's nodes were in the previous edge. 
				// So, add the nearest one to the previous node from the current edge, 
				// then add the second of the current edge's nodes (assumes a straight 
				// line between disconnected edges)
				double distance1 = Geography.distance(previousNode, currentEdge.getTnFrom());
				double distance2 = Geography.distance(previousNode, currentEdge.getTnTo());
				
				if (distance1 < distance2) {
					previousNode = currentEdge.getTnFrom();
					currentNode = currentEdge.getTnTo();
					currentFromTo = true;
				} else {
					previousNode = currentEdge.getTnTo();
					currentNode = currentEdge.getTnFrom();
					currentFromTo = false;
				}

				nodes.add(previousNode);
				if (edgesBetweenNodes != null) {
					edgesBetweenNodes.add(null);
					edgesBetweenNodesFromTo.add(null);
				}
			}
			
			nodes.add(currentNode);
			if (edgesBetweenNodes != null) {
				edgesBetweenNodes.add(currentSnapping);
				edgesBetweenNodesFromTo.add(currentFromTo);
			}
			previousSnapping = currentSnapping;
			previousEdge = currentEdge;
			previousNode = currentNode;
			previousFromTo = currentFromTo;
		}
		
		return nodes;
	}
	
	/**@return coords as 2D array, with lon,lat ordering - these are coords for each node*/
	public static LatLng[] nodeListToCoordsList(List<TaxiNode> nodes) {
		LatLng[] snappedCoordsDbl = new LatLng[nodes.size()];
		for (int i = 0; i < nodes.size(); i++) {
			snappedCoordsDbl[i] = new LatLng(nodes.get(i).getLatCoordinate(), nodes.get(i).getLonCoordinate());
		}
		
		return snappedCoordsDbl;
	}
	
	/**
	 * looks in a route for multiple runways or stands, and splits into single RW>S or S>RW routes
	 * This assumes that there will always be pairs on which to split! e.g. Stand,Stand (in then out), or runway,runway (takeoff then landing)
	 * No extra snappings are created to make up for singletons
	 */
	public List<RouteTaken> splitRoute(RouteTaken routeTaken) {
		boolean localDebug = false;
		if (localDebug) {
			printlnSafelyToSystemOut("Splitting route if necessary...");
		}
		
		List<Snapping> route = routeTaken.getSnappings();
		List<Integer> indicesWithStands = new ArrayList<Integer>();
		List<Integer> indicesWithRunways = new ArrayList<Integer>();
		String firstRunway = routeTaken.getFirstRunwayID();
		String lastRunway = routeTaken.getLastRunwayID();
		
		for (int i = 0; i < route.size(); i++) {
			Snapping s = route.get(i);
			
			if (s.getSnappedEdge().getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) {
				indicesWithStands.add(i);
			} else if (s.getSnappedEdge().containsRunwayOrRunwayConnectionNode()) {
				indicesWithRunways.add(i);
			}
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Runways");
			printlnSafelyToSystemOut(ArrayTools.toString(indicesWithRunways.toArray()));
			printlnSafelyToSystemOut("Stands");
			printlnSafelyToSystemOut(ArrayTools.toString(indicesWithStands.toArray()));
		}
		
		// NB - don't remove RW crossing manouvres! these will be when we have two RW edges consecutively in the path, but they are not adjacent
		// so remove these from the list before starting to split
		for (int i = indicesWithRunways.size() - 1; i > 0; i--) {
			int prev = indicesWithRunways.get(i - 1);
			int cur = indicesWithRunways.get(i);
			
			//if (route.get(cur).getSnappedEdge().isAdjacentTo(route.get(prev).getSnappedEdge())) {
			if (route.get(cur).getSnappedEdge().isAdjacentTo(route.get(prev).getSnappedEdge())) {
				indicesWithRunways.remove(i);
				indicesWithRunways.remove(i - 1);
				i--;
			}
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("After checking for crossings, runways");
			printlnSafelyToSystemOut(ArrayTools.toString(indicesWithRunways.toArray()));
		}
		
		// do we need to split?
		List<RouteTaken> rval = new ArrayList<RouteTaken>(indicesWithRunways.size() + indicesWithStands.size());
		while (indicesWithRunways.size() + indicesWithStands.size() >= 2) { // while we still have a start and an end point left
			// we do. so start at the beginning, and split at each index (whichever is next)
			int iStart;
			if (indicesWithStands.isEmpty() || (!indicesWithRunways.isEmpty() && (indicesWithRunways.get(0) < indicesWithStands.get(0)))) { // get first index from either of the lists
				iStart = indicesWithRunways.remove(0);
			} else {
				iStart = indicesWithStands.remove(0);
			}
			
			int iEnd;
			if (indicesWithStands.isEmpty() || (!indicesWithRunways.isEmpty() && (indicesWithRunways.get(0) < indicesWithStands.get(0)))) { // get second index from either of the lists
				iEnd = indicesWithRunways.remove(0);
			} else {
				iEnd = indicesWithStands.remove(0);
			}
			
			if (localDebug) {
				printlnSafelyToSystemOut("Creating sub route from " + iStart + " to " + iEnd);
			}
			
			RouteTaken reduced = routeTaken.subRoute(iStart, iEnd + 1);
			if (validateRoute(reduced)) {
				if (localDebug) {
					printlnSafelyToSystemOut("(valid)");
				}
				
				rval.add(reduced); // add 1 as it's exclusive
			} else {
				if (localDebug) {
					printlnSafelyToSystemOut("( not valid)");
				}
			}
		}
		
		// sort out runways
		// if only one route, depending on whether it's an arrival or departure, use the earliest or latest runway encountered respectively
		// if >1 route, assign runways for first and last routes only
		if (localDebug) {
			printlnSafelyToSystemOut("Sorting out runways:" + rval.size());
		}
		if (rval.size() == 1) {
			if (rval.get(0).getSnappings().get(0).getSnappedEdge().containsRunwayOrRunwayConnectionNode()) { // arrival
				rval.get(0).setActualRunwayUsed(firstRunway);
			} else if (rval.get(0).getSnappings().get(rval.get(0).getSnappings().size() - 1).getSnappedEdge().containsRunwayOrRunwayConnectionNode()) { // departure
				rval.get(0).setActualRunwayUsed(lastRunway);
			}
			if (localDebug) { 
				printlnSafelyToSystemOut("RW for 0: " + rval.get(0).getActualRunwayUsed());
			}
		} else if (rval.size() > 1) {
			rval.get(0).setActualRunwayUsed(firstRunway);
			rval.get(rval.size() - 1).setActualRunwayUsed(lastRunway);
			
			if (localDebug) { 
				printlnSafelyToSystemOut("RW for first: " + rval.get(0).getActualRunwayUsed());
				printlnSafelyToSystemOut("RW for last: " + rval.get(rval.size() - 1).getActualRunwayUsed());
			}
		}
		
		if (localDebug) {
			printlnSafelyToSystemOut("Route is now " + rval.size() + " route(s)");
		}
		
		return rval;
	}
	
	/**checks that a route has at least one stand and one runway*/
	private static boolean validateRoute(RouteTaken routeTaken) {
		List<Snapping> snappings = routeTaken.getSnappings();
		
		// start on stand, end on runway
		if ((snappings.get(0).getSnappedEdge().getEdgeType() == EdgeType.STAND_CONNECTION) && (snappings.get(snappings.size() - 1).getSnappedEdge().containsRunwayOrRunwayConnectionNode())){
			return true;
		// start on runway, end on stand
		} else if ((snappings.get(snappings.size() - 1).getSnappedEdge().getEdgeType() == EdgeType.STAND_CONNECTION) && (snappings.get(0).getSnappedEdge().containsRunwayOrRunwayConnectionNode())){
			return true;
		// start on stand, end on stand
		} else if ((snappings.get(0).getSnappedEdge().getEdgeType() == EdgeType.STAND_CONNECTION) && (snappings.get(snappings.size() - 1).getSnappedEdge().getEdgeType() == EdgeType.STAND_CONNECTION)){
			return false;
		// none of the above
		} else {
			return false;
		}
	}
	
	/**
	 * this only calcs for the edges 1...n-2, as the outer ones don't have both start and finish times;
	 * NaNs are added for these so the indices still match
	 * <p>
	 * a potential flaw here as it assumes that the points lie along the edge like this |---X--X-X-----X-------X-| and that they were
	 * travelled in a linear fashion (implying only one traversal of the edge)
	 * <p>
	 * If a reversal happened, then there is no connection between the order of the original points to show when this happened. Net result is
	 * that we get some cases where the times are wrong if an edge is traversed more than once.
	 * <p>
	 * It doesn't seem to happen often though, so for now, we'll just look for immediately adjacent edges and where they occur, set the
	 * times to null (this makes sense anyway as the aircraft probably didn't travel the full length of the edge). This will not catch
	 * situations where an edge is revisited later in the route. To catch this, if we ever detect a negative time, set time to NaN too 
	 * */
	private List<EdgeTime> calcTimesToTravelEdges(RouteTaken routeTaken, int aircraftNumberForOutput) {
		List<Snapping> snappingsBetweenNodes = routeTaken.getSnappings();
		List<Boolean> snappingsBetweenNodesDirection = routeTaken.getSnappingsFromTo();
		
		List<EdgeTime> rval = new ArrayList<EdgeTime>(snappingsBetweenNodes.size());
		
		// if a small number of edges, then we can't calc times
		if (snappingsBetweenNodes.size() == 0) {
			return rval;
		} else if (snappingsBetweenNodes.size() == 1) {
			rval.add(new EdgeTime(Double.NaN, null, null));
			return rval;
		} else if (snappingsBetweenNodes.size() == 2) {
			rval.add(new EdgeTime(Double.NaN, null, null));
			rval.add(new EdgeTime(Double.NaN, null, null));
			return rval;
		}	
		
		// add first edge's times (unknown)
		rval.add(new EdgeTime(Double.NaN, null, null));
		
		double cumulativeDistance = 0;
		double cumulativeTime = 0;
		for (int j = 1; j < snappingsBetweenNodes.size() - 1; j++) {
			Snapping previous = snappingsBetweenNodes.get(j - 1);
			boolean previousDirection = snappingsBetweenNodesDirection.get(j - 1);
			Snapping current = snappingsBetweenNodes.get(j);
			boolean currentDirection = snappingsBetweenNodesDirection.get(j);
			Snapping next = snappingsBetweenNodes.get(j + 1);
			boolean nextDirection = snappingsBetweenNodesDirection.get(j + 1);
			
			double time;
			Long inTime = null;
			Long outTime = null;
			double distance = current.getSnappedEdge().getLength();
			
			String calcDetail = previous + "\t" + current + "\t" + next;
			String calcText = "";
			if ((previous.getSnappedEdge() != current.getSnappedEdge()) && (current.getSnappedEdge() != next.getSnappedEdge())) {
				// work out the time taken on this edge.
				// figure out the closest real TimeCoordinates to the node at each end of this edge
				// work out how close the nodes are to those TCs
				// assume constant speed between the TCs
				// time to reach nodes is the timestamp coming from the previous TC, plus an appropriate fraction of the time to reach the next one
				Snapping.CoordTime latestTimeAtPrevious = previous.getLatestTimeAtCoord();
				Snapping.CoordTime earliestTimeAtCurrent = current.getEarliestTimeAtCoord();
				Snapping.CoordTime latestTimeAtCurrent = current.getLatestTimeAtCoord();
				Snapping.CoordTime earliestTimeAtNext = next.getEarliestTimeAtCoord();
				
				double distanceRemainingOnPrevious;
				if (previousDirection) { //from->to
					distanceRemainingOnPrevious = previous.getSnappedEdge().getLength() * (1-latestTimeAtPrevious.getFractionAlongEdgeForTime());
				} else { // to->from
					distanceRemainingOnPrevious = previous.getSnappedEdge().getLength() * (latestTimeAtPrevious.getFractionAlongEdgeForTime());
				}
				
				double distance1AlongCurrent;
				double distance2AlongCurrent;
				if (currentDirection) { //from->to
					distance1AlongCurrent = current.getSnappedEdge().getLength() * (earliestTimeAtCurrent.getFractionAlongEdgeForTime());
					distance2AlongCurrent = current.getSnappedEdge().getLength() * (1-latestTimeAtCurrent.getFractionAlongEdgeForTime());
				} else { // to->from
					distance1AlongCurrent = current.getSnappedEdge().getLength() * (1-earliestTimeAtCurrent.getFractionAlongEdgeForTime());
					distance2AlongCurrent = current.getSnappedEdge().getLength() * (latestTimeAtCurrent.getFractionAlongEdgeForTime());
				}
				
				double distanceAlongNext;
				if (nextDirection) { //from->to
					distanceAlongNext = next.getSnappedEdge().getLength() * (earliestTimeAtNext.getFractionAlongEdgeForTime());
				} else { // to->from
					distanceAlongNext = next.getSnappedEdge().getLength() * (1-earliestTimeAtNext.getFractionAlongEdgeForTime());
				}
	
				long previousTime = latestTimeAtPrevious.getTimeAtCoord();
				long earliestCurrentTime = earliestTimeAtCurrent.getTimeAtCoord();
				long latestCurrentTime = latestTimeAtCurrent.getTimeAtCoord();
				long nextTime = earliestTimeAtNext.getTimeAtCoord();
				
				long timePreviousToCurrent = earliestCurrentTime - previousTime;
				long timeCurrentToNext = nextTime - latestCurrentTime;
				
				double distancePreviousToCurrent = distanceRemainingOnPrevious + distance1AlongCurrent;
				double distanceCurrentToNext = distanceAlongNext + distance2AlongCurrent;
				
				double fraction1 = distanceRemainingOnPrevious / distancePreviousToCurrent;
				double fraction2 = distance2AlongCurrent / distanceCurrentToNext;
				
				inTime = previousTime + (long)(fraction1 * timePreviousToCurrent);
				outTime = latestCurrentTime + (long)(fraction2 * timeCurrentToNext);
				
				time = (outTime - inTime) / 1000.0; // also convert to seconds

				cumulativeDistance += distance;
				cumulativeTime += time;
				
				// also we want details of how the time was calculated
				calcText = "Successful";
				calcDetail += EDGETIMESDETAILS_OUT_SEPARATOR + latestTimeAtPrevious.getOriginalCoord() + EDGETIMESDETAILS_OUT_SEPARATOR + latestTimeAtPrevious.getTimeAtCoord() + EDGETIMESDETAILS_OUT_SEPARATOR + distanceRemainingOnPrevious + EDGETIMESDETAILS_OUT_SEPARATOR + 
						earliestTimeAtCurrent.getOriginalCoord() + EDGETIMESDETAILS_OUT_SEPARATOR + earliestTimeAtCurrent.getTimeAtCoord() + EDGETIMESDETAILS_OUT_SEPARATOR + distance1AlongCurrent + EDGETIMESDETAILS_OUT_SEPARATOR + 
						latestTimeAtCurrent.getOriginalCoord() + EDGETIMESDETAILS_OUT_SEPARATOR + latestTimeAtCurrent.getTimeAtCoord() + EDGETIMESDETAILS_OUT_SEPARATOR + distance2AlongCurrent + EDGETIMESDETAILS_OUT_SEPARATOR +
						earliestTimeAtNext.getOriginalCoord() + EDGETIMESDETAILS_OUT_SEPARATOR + earliestTimeAtNext.getTimeAtCoord() + EDGETIMESDETAILS_OUT_SEPARATOR + distanceAlongNext + EDGETIMESDETAILS_OUT_SEPARATOR +
						fraction1 + EDGETIMESDETAILS_OUT_SEPARATOR + fraction2 + EDGETIMESDETAILS_OUT_SEPARATOR + inTime + EDGETIMESDETAILS_OUT_SEPARATOR + outTime;
			} else { // current or next edge same as this one - a U-turn
				calcText = "PrevOrNextEdgeWasSame";
				time = Double.NaN;
			}
			
			if (time < 0) { // We don't want a negative time. Ever.
				time = Double.NaN;
				calcText += "|NEGATIVE_TIME";
			}
			
			rval.add(new EdgeTime(time, inTime, outTime));
			if (timesOut != null) {
				printlnSafelyToTimesOut(j + EDGETIMESDETAILS_OUT_SEPARATOR + aircraftNumberForOutput + EDGETIMESDETAILS_OUT_SEPARATOR + current.getSnappedEdge() + EDGETIMESDETAILS_OUT_SEPARATOR + taxiGen.getGMWTaxiEdgeID(current.getSnappedEdge()) + EDGETIMESDETAILS_OUT_SEPARATOR + distance + EDGETIMESDETAILS_OUT_SEPARATOR + time + EDGETIMESDETAILS_OUT_SEPARATOR + cumulativeDistance + EDGETIMESDETAILS_OUT_SEPARATOR + cumulativeTime + EDGETIMESDETAILS_OUT_SEPARATOR + (current.isTimeEstimated()?"Y":"N") + EDGETIMESDETAILS_OUT_SEPARATOR + calcText + EDGETIMESDETAILS_OUT_SEPARATOR + calcDetail);
			}
		}
		
		// add last edge's times
		Long secondLastOutTime = rval.get(rval.size() - 1).getOutTime();
		rval.add(new EdgeTime(Double.NaN, secondLastOutTime, null)); // we can't calc last time because we have no points beyond edge end
		
		// some final updates to figure out times that may have been missed based on neighbouring edges' times
		for (int i = 0; i < (rval.size() - 1); i++) {
			if ((rval.get(i).getInTime() == null) && (i > 0)) {
				if ((snappingsBetweenNodes.get(i-1).getSnappedEdge() != snappingsBetweenNodes.get(i).getSnappedEdge())) { // prev edge different to current, we can still get the in-time
					rval.get(i).setInTime(rval.get(i-1).getOutTime());
				}
			}
			if ((rval.get(i).getInTime() == null) && (i < (snappingsBetweenNodes.size() - 1))) {
				if ((snappingsBetweenNodes.get(i).getSnappedEdge() != snappingsBetweenNodes.get(i+1).getSnappedEdge())) { // next edge different to current, we can still get the out-time
					rval.get(i).setOutTime(rval.get(i+1).getInTime());
				}
			}
		}
		
		return rval;
	}
	
	private static List<TaxiNode> extractStandNodesFromPath(List<TaxiEdge> path) {
		List<TaxiNode> rval = new ArrayList<TaxiNode>();
		for (TaxiEdge taxiEdge : path) {
			if (taxiEdge.getEdgeType() == TaxiEdge.EdgeType.STAND_CONNECTION) {
				if (taxiEdge.getTnFrom().getNodeType() == NodeType.STAND) {
					rval.add(taxiEdge.getTnFrom());
				} else {
					rval.add(taxiEdge.getTnTo());
				}
			}
		}
		
		return rval;
	}
	
	private static List<TaxiNode> extractRunwayNodesFromPath(List<TaxiEdge> path) {
		List<TaxiNode> rval = new ArrayList<TaxiNode>(); // NB - ignore two adjacent taxiway crossings (that really is a crossing)
		for (int i = 0; i < path.size(); i++) {
			TaxiEdge taxiEdge = path.get(i);
			// check that the next one is not also a runway crossing - if it is, ignore both
			if ((i < path.size() - 1) && (path.get(i + 1).containsRunwayOrRunwayConnectionNode())) {
				i++; // skip next one too
			} else {
				if (taxiEdge.getTnFrom().getNodeType() == NodeType.RUNWAY_CROSSING) {
					rval.add(taxiEdge.getTnFrom());
				} else if (taxiEdge.getTnTo().getNodeType() == NodeType.RUNWAY_CROSSING) {
					rval.add(taxiEdge.getTnTo());
				}
			}
		}
		
		return rval;
	}
	
	private void printlnSafelyToSnappedOut(String line) {
		synchronized (this.snappedOut) {
			this.snappedOut.println("T" + threadNum + ":\t" + line);
		}
	}
	
	private void printlnSafelyToTimesOut(String line) {
		synchronized (this.timesOut) {
			this.timesOut.println("T" + threadNum + ":\t" + line);
		}
	}
	
	private void printlnSafelyToSystemOut(String line) {
		synchronized (System.out) {
			System.out.println("T" + threadNum + ":\t" + line);
		}
	}
	
	public void setkForStage2PathReduction(int kForStage2PathReduction) {
		this.kForStage2PathReduction = kForStage2PathReduction;
	}
	
	/** 
	 * looks at the snappings, and the direction in which they're traversed, and determines which runway the aircraft used 
	 * (including the direction of landing/takeoff)
	 */
	public List<String> determineRunwaysUsedByRoute(List<SnapTracksThread.Snapping> snappings, List<Boolean> snappingsFromTo) {
		List<String> runwayNames = new ArrayList<String>(snappings.size());
		for (int i = 0; i < snappings.size(); i++) {
			SnapTracksThread.Snapping s = snappings.get(i);
			boolean fromTo = snappingsFromTo.get(i).booleanValue();
			
			if (s.getSnappedEdge().getEdgeType() == EdgeType.RUNWAY) {
				 try {
					String meta = s.getSnappedEdge().getMeta();
					String[] runways = meta.split("/");
					double[] bearingsForRunway = new double[] {10 * Double.parseDouble(runways[0].replaceAll("\\D+", "")), 10 * Double.parseDouble(runways[1].replaceAll("\\D+", ""))}; // strip all non-numeric characters from runway names
					
					// the runway will be named xx/yy, the bearings/10
					// so we figure out the bearing that this edge was taken in, and pick whichever runway is closest in angle
					double bearingForThisEdge = Geography.bearing(s.getSnappedEdge());
					double bearingTravelled;
					if (fromTo) {
						bearingTravelled = bearingForThisEdge;
					} else {
						bearingTravelled = (180 + bearingForThisEdge) % 360;
					}
					
					// compare the bearings
					double diffRW1 = 180 - Math.abs(Math.abs(bearingTravelled-bearingsForRunway[0]) - 180);
					double diffRW2 = 180 - Math.abs(Math.abs(bearingTravelled-bearingsForRunway[1]) - 180);

					// whichever is the closest to the edge's bearing is the runway chosen
					if (diffRW1 < diffRW2) {
						runwayNames.add(runways[0].trim());
					} else {
						runwayNames.add(runways[1].trim());
					}
				 } catch (Exception e) { // any trouble, and we don't assign a runway
					 runwayNames.add(null);
				 }
			} else {
				runwayNames.add(null); // not a runway, add null
			}
		}
		
		return runwayNames;
	}
	
	
	/**wraps data associated with snapping a coordinate to an edge*/
	public static class Snapping {
		public class CoordTime {
			private double fractionAlongEdgeForTime;
			private long timeAtCoord;
			/**this might be null if it's an interpolated edge*/
			private LatLng originalCoord;
			public CoordTime (long timeAtCoord, double fractionAlongEdge, LatLng originalCoord) {
				this.timeAtCoord = timeAtCoord;
				this.fractionAlongEdgeForTime = fractionAlongEdge;
				this.originalCoord = originalCoord;
			}
			public double getFractionAlongEdgeForTime() {
				return fractionAlongEdgeForTime;
			}
			public long getTimeAtCoord() {
				return timeAtCoord;
			}
			public LatLng getOriginalCoord() {
				return originalCoord;
			}
		}
		
		private TimeCoordinate originalCoord;
		private LatLng snappedCoord;
		private double distanceFromEdge;
		private TaxiEdge snappedEdge;
		private List<CoordTime> timesAtCoord;
		private boolean timeEstimated;
		
		/**true if snapped from a coordinate, false if interpolated between two snapped edges (in which case the coords will be null and distanceFromEdge will be 0)*/
		private boolean snappedFromCoord;
		
		public Snapping(TimeCoordinate originalCoord, LatLng snappedCoord, double distanceFromEdge, TaxiEdge snappedEdge, long timeAtCoord, boolean timeEstimated) {
			this.originalCoord = originalCoord;
			this.snappedCoord = snappedCoord;
			this.distanceFromEdge = distanceFromEdge;
			this.snappedEdge = snappedEdge;
			this.timesAtCoord = new ArrayList<CoordTime>();
			this.timesAtCoord.add(new CoordTime(timeAtCoord, calcFractionAlongEdge(snappedEdge, snappedCoord), originalCoord.getCoord()));
			this.timeEstimated = timeEstimated;
			this.snappedFromCoord = true;
		}
		
		/**assumes time estimated is at midpoint of edge*/
		public Snapping(TaxiEdge snappedEdge, long timeAtCoord, boolean timeEstimated) {
			this.originalCoord = null;
			this.snappedCoord = null;
			this.distanceFromEdge = 0;
			this.snappedEdge = snappedEdge;
			this.timesAtCoord = new ArrayList<CoordTime>();
			this.timesAtCoord.add(new CoordTime(timeAtCoord, 0.5, null));
			this.timeEstimated = timeEstimated;
			this.snappedFromCoord = false;
		}
		
		/**assumes edge length is already calculated properly!*/
		private double calcFractionAlongEdge(TaxiEdge edge, LatLng snappedCoord) {
			double distanceAlongEdge = Geography.distance(edge.getTnFrom().getLatCoordinate(), edge.getTnFrom().getLonCoordinate(), snappedCoord.getLat(), snappedCoord.getLng());
			
			return distanceAlongEdge / edge.getLength();
		}
		
		public boolean isSnappedFromCoord() {
			return snappedFromCoord;
		}
		
		public boolean isTimeEstimated() {
			return timeEstimated;
		}
		
		public TaxiEdge getSnappedEdge() {
			return snappedEdge;
		}
		
		public List<CoordTime> getTimesAtCoord() {
			return timesAtCoord;
		}
		
		public CoordTime getLatestTimeAtCoord() {
			int indexLatest = 0;
			long latest = Long.MIN_VALUE;
			for (int i = 0; i < timesAtCoord.size(); i++) {
				if (timesAtCoord.get(i).timeAtCoord > latest) {
					latest = timesAtCoord.get(i).timeAtCoord;
					indexLatest = i;
				}
			}
			
			return timesAtCoord.get(indexLatest);
		}
		
		public CoordTime getEarliestTimeAtCoord() {
			int indexEarliest = 0;
			long earliest = Long.MAX_VALUE;
			for (int i = 0; i < timesAtCoord.size(); i++) {
				if (timesAtCoord.get(i).timeAtCoord < earliest) {
					earliest = timesAtCoord.get(i).timeAtCoord;
					indexEarliest = i;
				}
			}
			
			return timesAtCoord.get(indexEarliest);
		}
		
		public void addCoordTime(CoordTime ct) {
			this.timesAtCoord.add(ct);
		}
		
		public void addCoordTimes(List<CoordTime> ct) {
			this.timesAtCoord.addAll(ct);
		}
		
		@Override
		public String toString() {
			return "$" + this.snappedCoord + ";" + this.snappedEdge.toString() + ";"+ distanceFromEdge + ";" + this.originalCoord + "$";
		}
	}
	
	/**wraps a list of snappings and edgetimes representing a route taken by an aircraft*/
	public static class RouteTaken {
		private List<Snapping> snappings;
		private List<Boolean> snappingsFromTo;
		private List<EdgeTime> timesTaken;
		private List<String> runwaysUsed; // corresponds to each edge: nulls if edges are not runways
		private String firstRunwayID; // simplest way to keep track currently - look at first and last edges on runways
		private String lastRunwayID;
		private String actualRunwayUsed; // after splitting and tuning, this tracks the runway used for one movement
		public int gmwID;
		
		public RouteTaken(List<Snapping> snappings, List<Boolean> snappingsFromTo) {
			this.snappings = snappings;
			this.snappingsFromTo = snappingsFromTo;
			this.timesTaken = null;
			this.runwaysUsed = null;
			this.firstRunwayID = null;
			this.lastRunwayID = null;
			this.actualRunwayUsed = null;
			this.gmwID = -1;
		}
		
		public List<Snapping> getSnappings() {
			return snappings;
		}
		
		public List<Boolean> getSnappingsFromTo() {
			return snappingsFromTo;
		}
		
		public void setTimesTaken(List<EdgeTime> timesTaken) {
			this.timesTaken = timesTaken;
		}
		
		public List<EdgeTime> getTimesTaken() {
			return timesTaken;
		}
		
		public void setRunwaysUsed(List<String> runwaysUsed) {
			this.runwaysUsed = runwaysUsed;
			
			for (int i = 0; i < runwaysUsed.size(); i++) {
				if (runwaysUsed.get(i) != null) {
					firstRunwayID = runwaysUsed.get(i);
					break;
				}
			}
			for (int i = runwaysUsed.size() - 1; i >= 0; i--) {
				if (runwaysUsed.get(i) != null) {
					lastRunwayID = runwaysUsed.get(i);
					break;
				}
			}
		}
		
		public List<String> getRunwaysUsed() {
			return runwaysUsed;
		}
		
		public void setActualRunwayUsed(String actualRunwayUsed) {
			this.actualRunwayUsed = actualRunwayUsed;
		}
		
		public String getFirstRunwayID() {
			return firstRunwayID;
		}
		
		public String getLastRunwayID() {
			return lastRunwayID;
		}
		
		public String getActualRunwayUsed() {
			return actualRunwayUsed;
		}
		
		public void removeSnappingAtPosition(int pos) {
			this.snappings.remove(pos);
			this.snappingsFromTo.remove(pos);
			if ((timesTaken != null) && (timesTaken.size() > pos)) {
				this.timesTaken.remove(pos);
			}
			if ((runwaysUsed != null) && (runwaysUsed.size() > pos)) {
				this.runwaysUsed.remove(pos);
			}
		}
		
		public RouteTaken subRoute(int indexStartInclusive, int indexEndExclusive) {
			RouteTaken sub = new RouteTaken(this.snappings.subList(indexStartInclusive, indexEndExclusive), this.snappingsFromTo.subList(indexStartInclusive, indexEndExclusive));
			
			if (this.timesTaken != null) {
				sub.timesTaken = this.timesTaken.subList(indexStartInclusive, indexEndExclusive);
			}
			
			if (this.runwaysUsed != null) {
				sub.runwaysUsed = this.runwaysUsed.subList(indexStartInclusive, indexEndExclusive);
			}
			
			return sub;
		}
	}
	
	/**wraps several values related to the time that an AC traversed an edge*/
	public static class EdgeTime {
		private double timeTaken;
		private Long inTime;
		private Long outTime;
		
		public EdgeTime(double timeTaken, Long inTime, Long outTime) {
			this.timeTaken = timeTaken;
			this.inTime = inTime;
			this.outTime = outTime;
		}
		
		public double getTimeTaken() {
			return timeTaken;
		}
		
		public Long getInTime() {
			return inTime;
		}
		
		public void setInTime(Long inTime) {
			this.inTime = inTime;
		}
		
		public Long getOutTime() {
			return outTime;
		}
		
		public void setOutTime(Long outTime) {
			this.outTime = outTime;
		}
	}
	
	private static void debugSnappingsToKML(String filePrefix, List<List<Snapping>> snappings, List<Snapping> laterStage) {
		debugSnappingsToKML(filePrefix, snappings, laterStage, false, 0, null);
	}
	
	/**
	 * Exists purely for debugging purposes
	 * <br/><br/>
	 * This will generate KML showing the original coordinates, the "snapped" versions of them (pushed onto their edge), the edge,
	 * and optionally, the other coordinates with the time since the start of the movement and the traversal time for each edge.
	 * (in the latter case, the bundle for each edge is wrapped in a separate KML sub-document)
	 * <br/><br/>
	 * Interpolated edges (ie with no "original" points) are labelled with an I
	 * Edge numbers are just the index in the route, not the GM ID (this is added in brackets)
	 * Original time coordinates are also labelled with the edge they snapped to "Exx", an index to identify the point among all points for that edge, the fraction along the edge that the point is "fxxx", and the time in s since the start of the movement
	 * @param timeOffset - if showing times, this should be the first one, so that we can show seconds from start rather than timestamps 
	 * */
    private static void debugSnappingsToKML(String filePrefix, List<List<Snapping>> snappings, List<Snapping> laterStage, boolean addTimesToOriginalPoints, long timeOffset, List<EdgeTime> edgeTimes) {
		final Kml kml = KmlFactory.createKml();
		final Document document = kml.createAndSetDocument().withName(filePrefix).withOpen(true);
		final Style style = document.createAndAddStyle().withId("placemarkStyle");
		style.createAndSetIconStyle().withIcon(new Icon().withHref("http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png")).withColor("ffff7777").withScale(1);
		final Style styleSnapped = document.createAndAddStyle().withId("placemarkStyleSnapped");
		styleSnapped.createAndSetIconStyle().withIcon(new Icon().withHref("http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png")).withColor("ff7777ff").withScale(1);
		
		final Style styleSnappedEdge = document.createAndAddStyle().withId("linestyleSnappedEdge");
		styleSnappedEdge.createAndSetLineStyle()
		.withColor("ff00ffff")
		.withWidth(4.0d);

		if (snappings != null) {
			for (int i = 0; i < snappings.size(); i++) {
				if (snappings.get(i).size() > 0) {
					// add original point
					Placemark p = document.createAndAddPlacemark().withName("Original_" + i).withStyleUrl("#placemarkStyle");
					p.createAndSetPoint().addToCoordinates(snappings.get(i).get(0).originalCoord.getCoord().getLng(), snappings.get(i).get(0).originalCoord.getCoord().getLat());
				}
				
				for (int j = 0; j < snappings.get(i).size(); j++) {
					Snapping s = snappings.get(i).get(j);
					
					// add snapped version of point
					Placemark p = document.createAndAddPlacemark().withName("SnappedPoint_" + i + "_" + j).withStyleUrl("#placemarkStyleSnapped");
					p.createAndSetPoint().addToCoordinates(s.snappedCoord.getLng(), s.snappedCoord.getLat());
					
					LineString ls = document.createAndAddPlacemark().withName("SnappedEdge_" + i + "_" + j).withStyleUrl("#linestyleSnappedEdge")
							.createAndSetLineString();
	
					ls.addToCoordinates(s.getSnappedEdge().getTnFrom().getLonCoordinate() + "," + s.getSnappedEdge().getTnFrom().getLatCoordinate() + ",0");
					ls.addToCoordinates(s.getSnappedEdge().getTnTo().getLonCoordinate() + "," + s.getSnappedEdge().getTnTo().getLatCoordinate() + ",0");
				}
			}
		} else { // for later stage
			for (int j = 0; j < laterStage.size(); j++) {
				Snapping s = laterStage.get(j);
				Document documentForThisSnapping = document;
				
				// add original point (if it exists)
				String interp = "I";
				String timeTakenForEdge = addTimesToOriginalPoints ? timeTakenForEdge = "_" + Maths.roundDouble(edgeTimes.get(j).timeTaken, 2) + "s, " + Maths.roundDouble(s.getSnappedEdge().getLength() / edgeTimes.get(j).timeTaken, 2) + "m/s" : "";
				if (s.snappedCoord != null) {
					documentForThisSnapping = document.createAndAddDocument().withName("Edge_" + j + "(" + s.getSnappedEdge().getId() + ")" + timeTakenForEdge).withOpen(false);
					
					Placemark p = documentForThisSnapping.createAndAddPlacemark().withName("Original_E" + j + (addTimesToOriginalPoints ? "_" + s.snappedCoord : "")).withStyleUrl("#placemarkStyle");
					p.createAndSetPoint().addToCoordinates(s.originalCoord.getCoord().getLng(), s.originalCoord.getCoord().getLat());
					
					// add snapped version of point
					p = documentForThisSnapping.createAndAddPlacemark().withName("SnappedPoint_E" + j).withStyleUrl("#placemarkStyleSnapped");
					p.createAndSetPoint().addToCoordinates(s.snappedCoord.getLng(), s.snappedCoord.getLat());
					
					interp = "";
					if (addTimesToOriginalPoints) {
						int timeNo = 0;
						for (CoordTime ct : s.timesAtCoord) {
							p = documentForThisSnapping.createAndAddPlacemark().withName("OrgTime_E" + j + "(" + s.getSnappedEdge().getId() + ")_" + timeNo + "_f" + Maths.roundDouble(ct.fractionAlongEdgeForTime,2) + "_" + ((ct.timeAtCoord - timeOffset) / 1000) + "s").withStyleUrl("#placemarkStyleSnapped");
							p.createAndSetPoint().addToCoordinates(ct.originalCoord.getLng(), ct.originalCoord.getLat());
						}
					}
				}
				
				LineString ls = documentForThisSnapping.createAndAddPlacemark().withName("SnappedEdge_"+interp+"_" + j + "(" + s.getSnappedEdge().getId() + ")" + timeTakenForEdge).withStyleUrl("#linestyleSnappedEdge")
						.createAndSetLineString(); //.withExtrude(true); //.withTessellate(true);

				ls.addToCoordinates(s.getSnappedEdge().getTnFrom().getLonCoordinate() + "," + s.getSnappedEdge().getTnFrom().getLatCoordinate() + ",0");
				ls.addToCoordinates(s.getSnappedEdge().getTnTo().getLonCoordinate() + "," + s.getSnappedEdge().getTnTo().getLatCoordinate() + ",0");
			}
		}
		
		String filename = filePrefix + ".kml";
		KMLUtils.addGroundOverlayToKMLDocument(filename, document);
		
		try {
			kml.marshal(new File(filename));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
