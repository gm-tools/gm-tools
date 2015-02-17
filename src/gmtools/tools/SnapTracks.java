package gmtools.tools;

import gmtools.common.ArrayTools;
import gmtools.common.GroundMovementWriter;
import gmtools.common.KMLUtils;
import gmtools.common.Legal;
import gmtools.graph.EdgeClusters;
import gmtools.graph.TaxiEdge;
import gmtools.graph.TaxiNode;
import gmtools.graph.TaxiNode.NodeType;
import gmtools.parsers.ColumnIndices;
import gmtools.parsers.RawFlightTrackData;
import gmtools.parsers.RawFlightTrackData.Aircraft;
import gmtools.snaptracks.CleaningRawDataOutliers;
import gmtools.snaptracks.SnapTracksThread;
import gmtools.snaptracks.SnapTracksThread.EdgeTime;
import gmtools.snaptracks.SnapTracksThread.RouteTaken;
import gmtools.snaptracks.SnapTracksThread.Snapping;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
public class SnapTracks {
	public static boolean GLOBAL_DEBUG_CLEANING = false;
	public static boolean GLOBAL_DEBUG_SNAPPING = false;
	public static boolean GLOBAL_DEBUG_SNAPPING_SPLIT = false;
	public static boolean GLOBAL_DEBUG_SNAPPING_CACHE = false;
	public static boolean GLOBAL_DEBUG_LOAD_FILTERING = false;
	
	private static final String EDGE_TIMES_SEPARATOR = "\t";
	
	private static final String EDGETIMESDETAILS_OUT_HEADER_THREAD = "thread";
	private static final String EDGETIMESDETAILS_OUT_HEADER_INDEX = "index";
	private static final String EDGETIMESDETAILS_OUT_HEADER_AIRCRAFTNUMBER = "aircraftNumber";
	private static final String EDGETIMESDETAILS_OUT_HEADER_EDGELABEL = "edgeLabel";
	private static final String EDGETIMESDETAILS_OUT_HEADER_EDGEID = "edgeID";
	private static final String EDGETIMESDETAILS_OUT_HEADER_DISTANCE = "distance";
	private static final String EDGETIMESDETAILS_OUT_HEADER_TIME = "time";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CUMULATIVEDISTANCE = "cumulativeDistance";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CUMULATIVETIME = "cumulativeTime";
	private static final String EDGETIMESDETAILS_OUT_HEADER_ESTIMATED = "Estimated";
	private static final String EDGETIMESDETAILS_OUT_HEADER_DEBUGTEXT = "DebugText";
	private static final String EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING = "PrevSnapping";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING = "CurrentSnapping";
	private static final String EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING = "NextSnapping";
	private static final String EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING_ORGCOORD = "PrevSnapping_OrgCoord";
	private static final String EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING_TIMEATCOORD = "PrevSnapping_TimeAtCoord";
	private static final String EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING_DISTANCEREMAININGONEDGE = "PrevSnapping_DistanceRemainingOnEdge";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING1_ORGCOORD = "CurrentSnapping1_OrgCoord";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING1_TIMEATCOORD = "CurrentSnapping1_TimeAtCoord";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING1_DISTANCEALONGEDGE = "CurrentSnapping1_DistanceAlongEdge";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING2_ORGCOORD = "CurrentSnapping2_OrgCoord";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING2_TIMEATCOORD = "CurrentSnapping2_TimeAtCoord";
	private static final String EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING2_DISTANCEREMAININGONEDGE = "CurrentSnapping2_DistanceRemainingOnEdge";
	private static final String EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING_ORGCOORD = "NextSnapping_OrgCoord";
	private static final String EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING_TIMEATCOORD = "NextSnapping_TimeAtCoord";
	private static final String EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING_DISTANCEALONGEDGE = "NextSnapping_DistanceAlongEdge";
	private static final String EDGETIMESDETAILS_OUT_HEADER_FRACTION1 = "Fraction1";
	private static final String EDGETIMESDETAILS_OUT_HEADER_FRACTION2 = "Fraction2";
	private static final String EDGETIMESDETAILS_OUT_HEADER_TIME1 = "Time1";
	private static final String EDGETIMESDETAILS_OUT_HEADER_TIME2 = "Time2]";
	
	/**the most-recently snapped list of aircraft*/
	private List<Aircraft> aircraft;
	
	/**the routes taken through the taxiways by the most-recently snapped list of aircraft*/
	private List<RouteTaken>[] aircraftRoutes;
	
	/**coordinates of the routes taken by the most-recently snapped list of aircraft (for writing out KML etc)*/
	private LatLng[][][] flightpaths;
	
	/**the names of the most-recently snapped list of aircraft*/
	private String[] flightNames;
	
	/**wrapper for the graphs*/
	private TaxiGen taxiGen;
	
	private EdgeClusters edgeClusters;
	
	/**
	 *  Load a GM file, load a set of flight tracks, snap them, and write out a corresponding GM file
	 *  Usage: SnapTracks inputGMfile prefixForOutputFiles airportID flightTrackFile1;flightTrackFile2;flightTrackFile3 [options]
	 *  Options:
	 *    -alat=x : lat of airport, so distant points can be filtered out (required)
	 *    -alon=y : lon of airport, so distant points can be filtered out (required)
	 *    -start=n : start at flight number n (default=0)
	 *    -end=n : end at flight number n (default=no limit)
	 *    -noclean : disable cleaning of tracks (default is enabled)
	 *    -snapped : tracks are already snapped (format of file is different) (default=false)
	 *    -threads=4 : number of threads to use (default is whatever is returned by JVM for Runtime.getRuntime().availableProcessors())
	 *    -step=10 : step width in metres (default=10)
	 *    -steps=50 : number of steps out (default=50)
	 *    -d=10 : distance from an edge in metres for a coordinate to snap to it (default=10)
	 *    -b=1800 : if there is a gap of more than this in metres between points, split into two separate tracks (<0 to disable) (default=1800)
	 *    -etd=XXX_EdgeTimeDetails : attempt to pick up the snapped routes from the specified EDT file and write out the normal outputs
	 *    -min=n : minimum number of points in a track (after cleaning) near airport before we'll try snapping (default=10)
	 *    -clean_XXX=YYY : any parameters that need passed to cleaning algorithm
	 */
	public static void main(String[] args) {
//		args = "MAN_GM.txt MAN_GM_Test MAN MAN_TestTaxiTimes.txt -alat=53.35 -alon=-2.27 -steps=0".split("\\s+");
//		args = "I:/Data/STR/STR_GM.txt STR_GMTest_Snapped STRTest export/STRTest.txt -steps=100 -clean_M=15 -alat=48.691 -alon=9.216 -end=10".split(" ");
		
		boolean snapping = true; // default mode is to snap. Otherwise, load the specified EdgeTimeDetails file and try to retrieve the snappings from there
		String gmFile = null; // input GM file with airport layout
		String filePrefix = null; // prefix for snapped tracks and times output files
		String airportID = null; // used to determine whether flights are inbound or outbound
		String[] flightTracksFiles = null;
		double latAirport = Double.NaN;
		double lonAirport = Double.NaN;
		final boolean flightTracksFilesIncludedIntervals = true; // was originally optional
		boolean flightTracksFilesAlreadySnapped = false;
		int startFlight = 0; // inclusive
		int endFlight = -1; // exclusive; if this is less than the start flight, we go right to the end
		boolean cleanTracks = true;
		long breakTracksIfGapOverS = 30 * 60; // if there is a gap of more than this between points, split into two separate tracks (<0 to disable)
		int min = 10;
		double snapDistanceM = 10;
		String etdFile = null;
		
		final double airportRadius = 0.1;
		
		int numberOfThreads = Runtime.getRuntime().availableProcessors();
		
		// displacement params
		double stepWidthMetres = 10;
		int maxStepsOut = 50;
		
		List<String> defaultCleaningParams = new ArrayList<String>();
		
		Legal.printLicence("SnapTracks");
		if (args.length < 4) {
			printUsage();
			System.exit(1);
		}
		
		gmFile = args[0];
		filePrefix = args[1];
		airportID = args[2];
		flightTracksFiles = args[3].split(";");
		
		boolean argsOK = true;
		for (int i = 4; i < args.length; i++) {
			String a = args[i];
			
			try {
				if (a.startsWith("-alat")) {
					latAirport = Double.parseDouble(a.substring(6));
				} else if (a.startsWith("-alon")) {
					lonAirport = Double.parseDouble(a.substring(6));
				} else if (a.equals("-noclean")) {
					cleanTracks = false;
				} else if (a.startsWith("-threads")) {
					numberOfThreads = Integer.parseInt(a.substring(9));
				} else if (a.startsWith("-min")) {
					min = Integer.parseInt(a.substring(5));
				} else if (a.startsWith("-step=")) {
					stepWidthMetres = Double.parseDouble(a.substring(6));
				} else if (a.startsWith("-steps=")) {
					maxStepsOut = Integer.parseInt(a.substring(7));
				} else if (a.startsWith("-start=")) {
					startFlight = Integer.parseInt(a.substring(7));
				} else if (a.startsWith("-end=")) {
					endFlight = Integer.parseInt(a.substring(5));
				} else if (a.equals("-snapped")) {
					flightTracksFilesAlreadySnapped = true;
				} else if (a.startsWith("-b=")) {
					breakTracksIfGapOverS = Long.parseLong(a.substring(3));
				} else if (a.startsWith("-d=")) {
					snapDistanceM = Double.parseDouble(a.substring(3));
				} else if (a.startsWith("-etd=")) {
					snapping = false;
					etdFile = a.substring(5);
				} else if (a.startsWith("-clean_")) {
					String cp = a.substring(7);
					if (!cp.startsWith("-")) { // make sure there's a - at the start of the param for passing to CleaningRawDataOutliers.main()
						cp = "-" + cp;
					}
					defaultCleaningParams.add(cp);
				} else if (a.equalsIgnoreCase("-debugC")) {
					GLOBAL_DEBUG_CLEANING = true;
				} else if (a.equalsIgnoreCase("-debugS")) {
					GLOBAL_DEBUG_SNAPPING = true;
				} else if (a.equalsIgnoreCase("-debugSS")) {
					GLOBAL_DEBUG_SNAPPING_SPLIT = true;
				} else if (a.equalsIgnoreCase("-debugSC")) {
					GLOBAL_DEBUG_SNAPPING_CACHE = true;
				} else if (a.equalsIgnoreCase("-debugL")) {
					GLOBAL_DEBUG_LOAD_FILTERING = true;
				}
			} catch (Exception e) {
				System.err.println("Error parsing argument " + a);
				e.printStackTrace();
				argsOK = false;
			}
		}
		if (Double.isNaN(latAirport) || Double.isNaN(lonAirport)) {
			System.err.println("Please specify airport coordinates using -alat and -alon");
			System.exit(1);
		}
		
		if (!argsOK) {
			System.err.println("Exiting.");
			System.exit(1);
		}
		
		// ============= config done ============
		
		System.out.println("SnapTracks... config:");
		System.out.println("  Input GM taxiways file:" + gmFile);
		System.out.println("  Prefix for output files:" + filePrefix);
		System.out.println("  Airport ID, lat and lon:" + airportID + ", " + latAirport + ", " + lonAirport);
		System.out.println("  Flights to process:" + ((endFlight < startFlight)? "all" : startFlight + " to " + endFlight));
		System.out.println("  Threads:" + numberOfThreads);
		System.out.println("  Displacement step size (m), count:" + stepWidthMetres + ", " + maxStepsOut);
		System.out.println("  Min points near airport required to try snapping:" + min);
		System.out.println("  Max distance for coord to snap to edge (m):" + snapDistanceM);
		System.out.println("  Flight track files:" + ArrayTools.toString(flightTracksFiles, ","));
		System.out.println();
		
		String gmOutFile = filePrefix + "_withFlights.txt"; // name for output GM file
		if (breakTracksIfGapOverS < 0) {
			breakTracksIfGapOverS = Long.MAX_VALUE; // negative means no limit
		}
		
		// load existing GM file
		GroundMovementWriter gmw = new GroundMovementWriter(gmFile);
		
		// create an autotaxiways object from existing GM file
		TaxiGen at = new TaxiGen(gmw); 
		
		EdgeClusters edgeClusters = new EdgeClusters(at, 10, snapDistanceM);
		
		// clean track if necessary
		if (cleanTracks) {
			for (int i = 0; i < flightTracksFiles.length; i++) {
				System.out.println("Cleaning " + flightTracksFiles[i]);
				int dotIndex = flightTracksFiles[i].lastIndexOf(".");
				String cleanedFilename = (dotIndex >= 0 ? flightTracksFiles[i].substring(0, dotIndex) + "_cleaned" + flightTracksFiles[i].substring(dotIndex) : flightTracksFiles[i] + "_cleaned");
				List<String> cleaningParams = new ArrayList<String>(defaultCleaningParams);
				cleaningParams.add(0, flightTracksFiles[i]);
				cleaningParams.add(1, cleanedFilename);
				cleaningParams.add(2, "-alat="+latAirport);
				cleaningParams.add(3, "-alon="+lonAirport);
				CleaningRawDataOutliers.main(cleaningParams.toArray(new String[cleaningParams.size()]));
				flightTracksFiles[i] = cleanedFilename; // replace this so we use cleaned tracks for snapping
				System.out.println("...cleaned to " + cleanedFilename);
			}
		}
		
		// load raw flight tracks
		List<Aircraft> allAircraft = RawFlightTrackData.loadAircraft(flightTracksFilesAlreadySnapped, flightTracksFilesIncludedIntervals, "", flightTracksFiles, latAirport, lonAirport, airportRadius, airportID, breakTracksIfGapOverS, min); // this loads coords as lat/lon, snap method needs lon/lat, so swap below
		SnapTracks stm = new SnapTracks(at, edgeClusters);
		
		// snap tracks
		if (snapping) {
			stm.loadAndSnapAircraft(flightTracksFilesAlreadySnapped, flightTracksFilesIncludedIntervals, "", allAircraft, latAirport, lonAirport, airportRadius, airportID, filePrefix, numberOfThreads, breakTracksIfGapOverS, startFlight, endFlight, stepWidthMetres, maxStepsOut, min, snapDistanceM);
		
			// write out updated GM file
			Map<RouteTaken, Integer> gmwIDsForACs = addSnappedFlightTracksToGMFile(gmw, at, stm.aircraft, stm.aircraftRoutes);
			gmw.writeFile(gmOutFile);
			
			System.out.println("Writing edge taxi times");
			stm.edgeTaxiTimesToTSV(filePrefix + "_EdgeTaxiTimes.txt", gmwIDsForACs, at);
			
			graphNodesAndEdgesToKML(filePrefix + "_Snapped.kml", at, stm.flightpaths, stm.flightNames, stm.aircraft, stm.aircraftRoutes, gmwIDsForACs);
		} else {
			stm.loadSnappedRoutesFromEdgeTimeDetails(allAircraft, etdFile);
			
			// write out updated GM file
			Map<RouteTaken, Integer> gmwIDsForACs = addSnappedFlightTracksToGMFile(gmw, at, allAircraft, stm.aircraftRoutes);
			gmw.writeFile(gmOutFile);
			
			System.out.println("Writing edge taxi times");
			stm.edgeTaxiTimesToTSV(filePrefix + "_EdgeTaxiTimes.txt", gmwIDsForACs, at);
			
			// can't do kml at the moment because the original tracks still need extracted
		}
	}
	
	public static void printUsage() {
		System.out.println("Load a GM file, load a set of flight tracks, snap them, and write out a corresponding GM file");
		System.out.println("Usage: SnapTracks inputGMfile prefixForOutputFiles airportID flightTrackFile1;flightTrackFile2;flightTrackFile3 [options]");
		System.out.println();
		System.out.println("Options:");
		System.out.println("   -alat=x : lat of airport, so distant points can be filtered out (required)");
		System.out.println("   -alon=y : lon of airport, so distant points can be filtered out (required)");
		System.out.println("   -start=n : start at flight number n (default=0)");
		System.out.println("   -end=n : end at flight number n (default=no limit)");
		System.out.println("   -noclean : disable cleaning of tracks (default is enabled)");
		System.out.println("   -snapped : tracks are already snapped (format of file is different)");
		System.out.println("   -threads=4 : number of threads to use (default is whatever is returned by JVM for Runtime.getRuntime().availableProcessors())");
		System.out.println("   -step=10 : step width in metres (default=10)");
		System.out.println("   -steps=50 : number of steps out (default=50)");
		System.out.println("   -d=10 : distance from an edge in metres for a coordinate to snap to it (default=10)");
		System.out.println("   -etd=XXX_EdgeTimeDetails : attempt to pick up the snapped routes from the specified EDT file and write out the normal outputs");
		System.out.println("   -b=1800 : if there is a gap of more than this in metres between points, split into two separate tracks (<0 to disable) (default=1800)");
		System.out.println("   -min=n : minimum number of points in a track (after cleaning) near airport before we'll try snapping (default=10)");
		System.out.println("   -clean_XXX=YYY : any parameters that need passed to cleaning algorithm");
		System.out.println();
	}

	@SuppressWarnings("unchecked")
	public SnapTracks(TaxiGen at, EdgeClusters ec) {
		this.taxiGen = at;
		this.edgeClusters = ec;
		this.aircraft = null;
		
		// no aircraft to snap yet
		this.aircraft = Arrays.asList(new Aircraft[0]);
		this.aircraftRoutes = new List[0];
		this.flightpaths = new LatLng[0][][];
		this.flightNames = new String[0];
	}
	
	/**
	 * @param flightTracksFilesAlreadySnapped
	 * @param flightTracksFilesIncludedIntervals
	 * @param basedir
	 * @param flightTracksFiles
	 * @param latAirport
	 * @param lonAirport
	 * @param airportRadius
	 * @param airportID
	 * @param filePrefix
	 * @param numThreads
	 * @param limit - number of aircraft to stop after (-1 for no limit, no limit)
	 * @param min - min number of points in a track (after cleaning) near airport before we'll try snapping
	 */
	@SuppressWarnings("unchecked")
	private void loadAndSnapAircraft(boolean flightTracksFilesAlreadySnapped, boolean flightTracksFilesIncludedIntervals, String basedir, List<Aircraft> allAircraft, double latAirport, double lonAirport, double airportRadius, String airportID, String filePrefix, int numThreads, long breakTracksIfGapOverS, int startFlight, int endFlight, double stepWidthMetres, int maxStepsOut, int min, double snapDistanceM) {
		System.out.println("Snapping flights");
		
		PrintStream snappedOut = null;
		try {
			if (!flightTracksFilesAlreadySnapped) {
				snappedOut = new PrintStream(new FileOutputStream(filePrefix + "_SnappedTracks.txt"));
				snappedOut.println("Thread" + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + RawFlightTrackData.HEADER_ID + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + RawFlightTrackData.HEADER_ORIGIN + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + RawFlightTrackData.HEADER_DESTINATION + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + "StandsVisited" + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + "RunwaysUsed" + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + "SnappedSuccessfullyToTaxiways" + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + "LatAddedToRawValues" + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SUBSEPARATOR + "LonAddedToRawValues" + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + RawFlightTrackData.HEADER_TRACK + SnapTracksThread.SNAPPED_TRACKS_OUTPUT_SEPARATOR + RawFlightTrackData.HEADER_FIRSTTIMESTAMP);
			}
		} catch (IOException e) {
			System.err.println("Error opening snap file");
			e.printStackTrace();
		}
		PrintStream timesOut = null;
		try {
			if (flightTracksFilesIncludedIntervals) {
				timesOut = new PrintStream(new FileOutputStream(filePrefix + "_EdgeTimeDetails.txt"));
				timesOut.println("thread" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "index" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "aircraftNumber" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "edgeLabel" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "edgeID" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "distance" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "time" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "cumulativeDistance" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "cumulativeTime" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "Estimated" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "DebugText" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "PrevSnapping" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "CurrentSnapping" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "NextSnapping" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "PrevSnapping_OrgCoord" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "PrevSnapping_TimeAtCoord" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "PrevSnapping_DistanceRemainingOnEdge" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "CurrentSnapping1_OrgCoord" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "CurrentSnapping1_TimeAtCoord" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "CurrentSnapping1_DistanceAlongEdge" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "CurrentSnapping2_OrgCoord" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "CurrentSnapping2_TimeAtCoord" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "CurrentSnapping2_DistanceRemainingOnEdge" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "NextSnapping_OrgCoord" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "NextSnapping_TimeAtCoord" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "NextSnapping_DistanceAlongEdge" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "Fraction1" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "Fraction2" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "Time1" + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + "Time2");
				timesOut.println(EDGETIMESDETAILS_OUT_HEADER_THREAD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_INDEX + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_AIRCRAFTNUMBER + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_EDGELABEL + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_EDGEID + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_DISTANCE + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_TIME + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CUMULATIVEDISTANCE + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CUMULATIVETIME + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_ESTIMATED + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_DEBUGTEXT + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING_ORGCOORD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING_TIMEATCOORD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING_DISTANCEREMAININGONEDGE + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING1_ORGCOORD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING1_TIMEATCOORD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING1_DISTANCEALONGEDGE + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING2_ORGCOORD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING2_TIMEATCOORD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_CURRENTSNAPPING2_DISTANCEREMAININGONEDGE + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING_ORGCOORD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING_TIMEATCOORD + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING_DISTANCEALONGEDGE + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_FRACTION1 + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_FRACTION2 + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_TIME1 + SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR + EDGETIMESDETAILS_OUT_HEADER_TIME2);
			}
		} catch (IOException e) {
			System.err.println("Error opening snap file");
			e.printStackTrace();
		}
		
		this.aircraft = new ArrayList<Aircraft>();

		if ((startFlight > 0) || (endFlight > startFlight)) {
			if (startFlight < 0) {
				startFlight = 0;
			}
			if ((endFlight < startFlight) || (endFlight > allAircraft.size())) {
				endFlight = allAircraft.size();
			}
			
			this.aircraft = allAircraft.subList(startFlight, endFlight);
		} else {
			this.aircraft = allAircraft;
		}
		
		System.out.println("Loaded " + aircraft.size() + " aircraft");
		
		this.flightpaths = new LatLng[aircraft.size()][][];
		this.aircraftRoutes = new List[aircraft.size()];
		this.flightNames = new String[aircraft.size()];
		
		List<Integer> indicesToProcess = new ArrayList<Integer>(aircraft.size());
		for (int i = 0; i < aircraft.size(); i++) {
			indicesToProcess.add(i);
		}
		
		if (numThreads > aircraft.size()) {
			System.out.println("More threads (" + numThreads + ") than aircraft tracks (" + aircraft.size() + "). Reducing thread count.");
			numThreads = aircraft.size();
		}
		SnapTracksThread[] snapThreads = new SnapTracksThread[numThreads];
		for (int i = 0; i < snapThreads.length; i++) {
			snapThreads[i] = new SnapTracksThread(i, aircraft, indicesToProcess, taxiGen.getGraphWholeAirport(), flightpaths, aircraftRoutes, flightNames, flightTracksFilesIncludedIntervals, stepWidthMetres, maxStepsOut, snapDistanceM, taxiGen, edgeClusters, snappedOut, timesOut);
			snapThreads[i].start();
		}
		
		for (int i = 0; i < snapThreads.length; i++) {
			try {
				snapThreads[i].join();
			} catch (InterruptedException e) {}
		}

		if (snappedOut != null) {
			snappedOut.close();
		}
		if (timesOut != null) {
			timesOut.close();
		}
		
		System.out.println("Snapping complete.");
	}
	
	private void edgeTaxiTimesToTSV(String fileName, Map<RouteTaken, Integer> gmwIDsForACs, TaxiGen at) {
		String separator = EDGE_TIMES_SEPARATOR;
		
		try {
			PrintStream out = new PrintStream(new FileOutputStream(fileName));
			out.println("AircraftSeqNoInGM" + separator + "AircraftIDInOriginalData" + separator + "AircraftLabel" + separator + "RouteNumber" + separator + "EdgeLabel" + separator + "EdgeID" + separator + "EarliestTimeAtCoord" + separator + "LatestTimeAtCoord" + separator + "EdgeLengthMetres" + separator + "TimeTakenSeconds" + separator + "EdgeInTime" + separator + "EdgeOutTime" + separator + "StartNode" + separator + "EndNode");
			
			for (int acNum = 0; acNum < aircraftRoutes.length; acNum++) {
				List<RouteTaken> routesForAC = aircraftRoutes[acNum];

				for (int routeNum = 0; routeNum < aircraftRoutes[acNum].size(); routeNum++) {
					RouteTaken route = routesForAC.get(routeNum);
					int gmwID = gmwIDsForACs.get(route);
					
					for (int i = 0; i < route.getSnappings().size(); i++) {
						TaxiEdge te = route.getSnappings().get(i).getSnappedEdge();
						Integer eid = taxiGen.getGMWTaxiEdgeID(te);
						int edgeID = (eid != null) ? eid.intValue() : -1;
						int startNodeID = at.getGMWTaxiNodeID((route.getSnappingsFromTo().get(i).booleanValue() ? te.getTnFrom() : te.getTnTo()));
						int endNodeID = at.getGMWTaxiNodeID((route.getSnappingsFromTo().get(i).booleanValue() ? te.getTnTo() : te.getTnFrom()));
						
						out.println(gmwID + separator + acNum + separator + aircraft.get(acNum).getLabel() + separator + routeNum + separator + te + separator + edgeID + separator + route.getSnappings().get(i).getEarliestTimeAtCoord().getTimeAtCoord() + separator + route.getSnappings().get(i).getLatestTimeAtCoord().getTimeAtCoord() + separator + route.getSnappings().get(i).getSnappedEdge().getLength() + separator + route.getTimesTaken().get(i).getTimeTaken() + separator + route.getTimesTaken().get(i).getInTime() + separator + route.getTimesTaken().get(i).getOutTime() + separator + startNodeID + separator + endNodeID);
					}
				}
			}
			
			out.close();
		} catch (IOException e) {
			System.err.println("Error writing out taxi times");
			e.printStackTrace();
		}
	}
	
	/**@return GMW indices for Routes (not ACs, as each may have >1 route)*/
	private static Map<RouteTaken,Integer> addSnappedFlightTracksToGMFile(GroundMovementWriter gmw, TaxiGen at, List<Aircraft> aircraft, List<RouteTaken>[] routes) {
		@SuppressWarnings("unchecked")
		List<Integer>[] routeIDsPerAircraft = new List[aircraft.size()];
		
		int acNum = 0;
		for (List<RouteTaken> routesForThisAC : routes) {
			routeIDsPerAircraft[acNum] = new ArrayList<Integer>();
			
			if (routesForThisAC != null) {
				for (RouteTaken route : routesForThisAC) {
					if (route != null) {
						List<Snapping> l = route.getSnappings();
						
						int[] path = new int[l.size()];
						for (int i = 0; i < path.length; i++) {
							path[i] = at.getGMWTaxiEdgeID(l.get(i).getSnappedEdge());
						}
						GroundMovementWriter.Route r = new GroundMovementWriter.Route(path);
						gmw.addRoute(r);
						routeIDsPerAircraft[acNum].add(r.getSeqNo()); // store route IDs for each aircraft
					}
				}
			}
			
			acNum++;
		}
		
		Map<RouteTaken, Integer> gmwIDsForACs = new HashMap<RouteTaken, Integer>();
		for (acNum = 0; acNum < aircraft.size(); acNum++) {
			// get routes, and for each, get start/end points
			List<RouteTaken> routesForThisAC = routes[acNum];
			long startTimes[] = new long[routesForThisAC.size()];
			long endTimes[] = new long[routesForThisAC.size()];
			TaxiNode[] startNodes = new TaxiNode[routesForThisAC.size()];
			TaxiNode[] endNodes = new TaxiNode[routesForThisAC.size()];
			int[] startIDs = new int[routesForThisAC.size()];
			int[] endIDs = new int[routesForThisAC.size()];
			String[] runwaysUsed = new String[routesForThisAC.size()];
			for (int i = 0; i < routesForThisAC.size(); i++) {
				RouteTaken currentRouteForThisAC = routesForThisAC.get(i);
				TaxiEdge startEdge = currentRouteForThisAC.getSnappings().get(0).getSnappedEdge();
				TaxiEdge endEdge = currentRouteForThisAC.getSnappings().get(currentRouteForThisAC.getSnappings().size() - 1).getSnappedEdge();
				
				// pick to or from node in start/end edges based on direction travelled along those edges
				startNodes[i] = currentRouteForThisAC.getSnappingsFromTo().get(0) ? startEdge.getTnFrom() : startEdge.getTnTo();
				endNodes[i] = currentRouteForThisAC.getSnappingsFromTo().get(currentRouteForThisAC.getSnappings().size() - 1) ? endEdge.getTnTo() : endEdge.getTnFrom();

				startIDs[i] = at.getGMWTaxiNodeID(startNodes[i]);
				endIDs[i] = at.getGMWTaxiNodeID(endNodes[i]);
				
				// if we start on a stand, pick the latest time at the edge. if we finish on a stand, pick the earliest time at the edge.
				// with runways, at start we pick the earliest time, and at end we pick the latest
				if (startEdge.containsRunwayOrRunwayConnectionNode()) {
					startTimes[i] = currentRouteForThisAC.getSnappings().get(0).getEarliestTimeAtCoord().getTimeAtCoord();
				} else {
					startTimes[i] = currentRouteForThisAC.getSnappings().get(0).getLatestTimeAtCoord().getTimeAtCoord();
				}
				
				if (endEdge.containsRunwayOrRunwayConnectionNode()) {
					endTimes[i] = currentRouteForThisAC.getSnappings().get(currentRouteForThisAC.getSnappings().size() - 1).getLatestTimeAtCoord().getTimeAtCoord();
				} else {
					endTimes[i] = currentRouteForThisAC.getSnappings().get(currentRouteForThisAC.getSnappings().size() - 1).getEarliestTimeAtCoord().getTimeAtCoord();
				}
				
				runwaysUsed[i] = currentRouteForThisAC.getActualRunwayUsed();
			}
			
			// things that are constant to the aircraft regardless of route
			int speedProfile = 1;
			int appearanceTime = 0;
			double speedMin = 1;
			double speedIdeal = 1;
			double speedMax = 1;
			int weightClass = 1;
			int sidRoute = 1;
			int takeoffSpeedGroup = 1;
			
			for (int i = 0; i < routesForThisAC.size(); i++) {
				// assumes that track has been split, so we just need to look at the ends.
				// if we start on a runway and end on a stand, it's an arrival
				// if we start on a stand and end on a runway, it's a departure
				// otherwise, wecan't say what's going on
				GroundMovementWriter.Aircraft.Type type;
				if ((startNodes[i].getNodeType() == NodeType.RUNWAY_CROSSING) && (endNodes[i].getNodeType() == NodeType.STAND)) {
					type = GroundMovementWriter.Aircraft.Type.arrival;
				} else if (endNodes[i].getNodeType() == NodeType.RUNWAY_CROSSING && (startNodes[i].getNodeType() == NodeType.STAND)) {
					type = GroundMovementWriter.Aircraft.Type.departure;
				} else {
					type = GroundMovementWriter.Aircraft.Type.other;
				}
				
				long[] startTimesForThisRoute = new long[] {startTimes[i], startTimes[i], startTimes[i]};
				long[] endTimesForThisRoute = new long[] {endTimes[i], endTimes[i], endTimes[i]};
				GroundMovementWriter.Aircraft gmwAC = new GroundMovementWriter.Aircraft(type, startIDs[i], endIDs[i], startTimesForThisRoute, endTimesForThisRoute, speedProfile);
				
				gmwAC.setAppearanceTime(appearanceTime);
				gmwAC.setSidRoute(sidRoute);
				gmwAC.setSpeedIdeal(speedIdeal);
				gmwAC.setSpeedMax(speedMax);
				gmwAC.setSpeedMin(speedMin);
				gmwAC.setTakeoffSpeedGroup(takeoffSpeedGroup);
				gmwAC.setWeightClass(weightClass);
				
				if (runwaysUsed[i] != null) {
					gmwAC.setRunwayUsed(runwaysUsed[i]);
				}
				
				int[] routeIDs = {routeIDsPerAircraft[acNum].get(i)};
				gmwAC.setRouteIDs(routeIDs);
				
				gmw.addAircraft(gmwAC);
				gmwIDsForACs.put(routesForThisAC.get(i), gmwAC.getSeqNo());
			} // end of loop over routes for this AC
		} // end of loop over ACs
		
		return gmwIDsForACs;
	}
	
	/**
	 * the snapping threads write to XXX_EdgeTimeDetails.txt as they go. if for some reason the run failed and couldn't write the GM and _EdgeTaxiTimes files
	 * then this will load the snapped tracks from the EdgeTimeDetails ready to process without needing to re-snap (unfortunately at the moment, this doesn't
	 * have any indication of unsnapped tracks, and we don't have information about displacement etc so we can't used this to resume a run all that easily.
	 * Need to think some more about that. Might be possible to get the displacement based on the org coords in the details file - better would be just to
	 * change the purpose of this file slightly, adding the missing data)
	 * this still loads the aircraft details from the original data - this is matched against AircraftNumber in the EdgeTimeDetails file
	 * */
	@SuppressWarnings("unchecked")
	private void loadSnappedRoutesFromEdgeTimeDetails(List<Aircraft> aircraft, String edgeTimeDetailsFile) {
		System.out.println("Attempting to retrieve data for " + aircraft.size() + " aircraft.");
		
		this.aircraft = aircraft;
		
		// just needed to access some methods later on
		SnapTracksThread stt = new SnapTracksThread(0, aircraft, Collections.<Integer>emptyList(), this.taxiGen.getGraphWholeAirport(), null, this.aircraftRoutes, this.flightNames, false, 0, 0, 0, this.taxiGen, this.edgeClusters, null, null);
		
		// read file first - copy lines for each aircraft into a separate list
		List<List<String[]>> linesForEachAircraft = new ArrayList<>(aircraft.size());
		for (int i = 0; i < aircraft.size(); i++) {
			linesForEachAircraft.add(null);
		}
		
		int count = 0;
		ColumnIndices columnIndices = null;
		
		try {
			BufferedReader in = new BufferedReader(new FileReader(edgeTimeDetailsFile));
			
			String[] header = in.readLine().split(SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR);
			columnIndices = new ColumnIndices(header, edgeTimeDetailsFile);
			
			String line;
			while ((line = in.readLine()) != null) {
				String[] cols = line.split(SnapTracksThread.EDGETIMESDETAILS_OUT_SEPARATOR);
				
				// parse AC number
				int aircraftNumber = Integer.parseInt(cols[columnIndices.getColumnIndex(EDGETIMESDETAILS_OUT_HEADER_AIRCRAFTNUMBER, true)]);
				
				// add line to store
				List<String[]> l = linesForEachAircraft.get(aircraftNumber);
				if (l == null) {
					l = new ArrayList<>();
					linesForEachAircraft.set(aircraftNumber, l);
					count++;
				}
				
				l.add(cols);
			}
			
			in.close();
		} catch (IOException e) {
			System.err.println("Exception when reading EdgeTimeDetails file " + edgeTimeDetailsFile);
			e.printStackTrace();
			System.exit(1);
		}
		
		System.out.println("Found tracks for " + count + " aircraft before stage 2 splitting.");
		
		// need to update this.aircraftRoutes and this.aircraft - currently we can also do names, might as well instantiate all of the following
		this.aircraftRoutes = new List[aircraft.size()];
		this.flightpaths = new LatLng[aircraft.size()][][];
		this.flightNames = new String[aircraft.size()];
		for (int aircraftNum = 0; aircraftNum < aircraft.size(); aircraftNum++) {
			List<String[]> lines = linesForEachAircraft.get(aircraftNum);
			if (lines != null) {
				System.out.println("Processing AC " + aircraftNum);
				
				// create snappings representing the lines
				List<Snapping> snappings = new ArrayList<>();
				List<EdgeTime> edgeTimes = new ArrayList<>();
				
				// add first edge's times (unknown) - (for which there's no timing so it doesn't appear)
				// details are in the first line of "lines"
				snappings.add(Snapping.fromToString(lines.get(0)[columnIndices.getColumnIndex(EDGETIMESDETAILS_OUT_HEADER_PREVSNAPPING, true)], this.taxiGen));
				edgeTimes.add(new EdgeTime(Double.NaN, null, null));
				
				for (String[] line : lines) {
					TaxiEdge te = this.taxiGen.getEdgeByGMWId(Integer.parseInt(line[columnIndices.getColumnIndex(EDGETIMESDETAILS_OUT_HEADER_EDGEID, true)]));
					
					//System.out.println("Found edge " + te);
					
					Snapping snapping = new Snapping(te, 1, false);
					snappings.add(snapping);
					
					if (line[columnIndices.getColumnIndex(EDGETIMESDETAILS_OUT_HEADER_DEBUGTEXT, true)].equals("Successful")) {
						long inTime = Long.parseLong(line[columnIndices.getColumnIndex(EDGETIMESDETAILS_OUT_HEADER_TIME1, true)]);
						long outTime = Long.parseLong(line[columnIndices.getColumnIndex(EDGETIMESDETAILS_OUT_HEADER_TIME2, true)]);
						double time = Double.parseDouble(line[columnIndices.getColumnIndex(EDGETIMESDETAILS_OUT_HEADER_TIME, true)]);
						
						EdgeTime edgeTime = new EdgeTime(time, inTime, outTime);
						edgeTimes.add(edgeTime);
					} else { // debug text is "PrevOrNextEdgeWasSame"
						EdgeTime edgeTime = new EdgeTime(Double.NaN, null, null);
						edgeTimes.add(edgeTime);
					}
				}
				
				// taken from calcTimesToTravelEdges()
				// add last edge's times
				snappings.add(Snapping.fromToString(lines.get(lines.size()-1)[columnIndices.getColumnIndex(EDGETIMESDETAILS_OUT_HEADER_NEXTSNAPPING, true)], this.taxiGen));
				edgeTimes.add(new EdgeTime(Double.NaN, null, null));
				
				// some final updates to figure out times that may have been missed based on neighbouring edges' times
				for (int i = 0; i < (edgeTimes.size() - 1); i++) {
					if ((edgeTimes.get(i).getInTime() == null) && (i > 0)) {
						if ((snappings.get(i-1).getSnappedEdge() != snappings.get(i).getSnappedEdge())) { // prev edge different to current, we can still get the in-time
							edgeTimes.get(i).setInTime(edgeTimes.get(i-1).getOutTime());
						}
					}
					if ((edgeTimes.get(i).getInTime() == null) && (i < (snappings.size() - 1))) {
						if ((snappings.get(i).getSnappedEdge() != snappings.get(i+1).getSnappedEdge())) { // next edge different to current, we can still get the out-time
							edgeTimes.get(i).setOutTime(edgeTimes.get(i+1).getInTime());
						}
					}
				}
				
				// now calc directions and create route object
				List<Snapping> snappingsBetweenNodes = new ArrayList<Snapping>(); // this is different to route; it will have duplicates and nulls as it represents the actual edges travelled over in the order they were travelled. However, any edges here will be in routeEdges, in the same order
				List<Boolean> snappingsBetweenNodesDirection = new ArrayList<Boolean>();
				SnapTracksThread.snappingListToNodeList(snappings, snappingsBetweenNodes, snappingsBetweenNodesDirection); // don't care about the node list that this method generates
				RouteTaken routeTaken = new RouteTaken(snappingsBetweenNodes, snappingsBetweenNodesDirection);		
				routeTaken.setTimesTaken(edgeTimes);
				
				// removeRunwaysFromRoute(RouteTaken), called after time calcs in snapRouteToGraph()
				SnapTracksThread.removeRunwaysFromRoute(routeTaken);
				
				// split routes method in STT class is called after snapRouteToGraph() returns
				List<RouteTaken> splitRoutes = stt.splitRoute(routeTaken);
				
				aircraftRoutes[aircraftNum] = splitRoutes;
			} else {
				aircraftRoutes[aircraftNum]  = new ArrayList<RouteTaken>();
				System.out.println("No data for AC " + aircraftNum);
			}
			
			// we can always add the following data for an aircraft
			flightNames[aircraftNum] = aircraft.get(aircraftNum).toString();
		}
	}
	
	/**in KML, nodes identified by GM_ID-AT_ID-meta and edges by GM_ID-AT_unique_name*/
    private static void graphNodesAndEdgesToKML(String filename, TaxiGen at, LatLng[][][] flightpaths, String[] flights, List<Aircraft> aircraft, List<RouteTaken>[] routes, Map<RouteTaken, Integer> gmwIdsForACs) {
    	Collection<TaxiNode> nodes = at.getAllNodes().values(); 
    	Collection<TaxiEdge> edges = at.getAllEdges();
		final Kml kml = KmlFactory.createKml();
		final Document document = kml.createAndSetDocument().withName(filename).withOpen(true);
		final Document documentTaxiwaysE = document.createAndAddDocument().withName("TaxiwaysEdges").withOpen(false);
		final Document documentTaxiwaysN = document.createAndAddDocument().withName("TaxiwaysNodes").withOpen(false);
		final Document documentFlightpaths = document.createAndAddDocument().withName("Flightpaths").withOpen(true);
		final Style styleOSM = documentTaxiwaysN.createAndAddStyle().withId("placemarkStyleGates");
		styleOSM.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor("ffff7777").withScale(1);
		
		final Style styleTaxiway = documentTaxiwaysE.createAndAddStyle().withId("linestyleTaxiway");
		styleTaxiway.createAndSetLineStyle()
		.withColor("550000ff")
		.withWidth(4.0d);

		final Style styleTaxiwayToGate = documentTaxiwaysE.createAndAddStyle().withId("linestyleTaxiwayToGate");
		styleTaxiwayToGate.createAndSetLineStyle()
		.withColor("55ff0000")
		.withWidth(4.0d);
		
		final Style styleRunway = documentTaxiwaysE.createAndAddStyle().withId("linestyleRunway");
		styleRunway.createAndSetLineStyle()
		.withColor("5500ff00")
		.withWidth(4.0d);

		final Style styleOriginalFP = documentFlightpaths.createAndAddStyle().withId("linestyleOriginalFlightpath");
		styleOriginalFP.createAndSetLineStyle()
		.withColor("ff55ee00")
		.withWidth(4.0d);

		final Style styleDisplacedFP = documentFlightpaths.createAndAddStyle().withId("linestyleDisplacedFlightpath");
		styleDisplacedFP.createAndSetLineStyle()
		.withColor("ffee00ee")
		.withWidth(4.0d);

		final Style styleSnappedFP = documentFlightpaths.createAndAddStyle().withId("linestyleSnappedFlightpath");
		styleSnappedFP.createAndSetLineStyle()
		.withColor("ff00ffff")
		.withWidth(4.0d);
		
		final Style styleUnSnappedPoint = documentFlightpaths.createAndAddStyle().withId("unsnappedPoint");
		styleUnSnappedPoint.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor("ff0077ff").withScale(1);
		final Style styleSnappedPoint = documentFlightpaths.createAndAddStyle().withId("snappedPoint");
		styleSnappedPoint.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor("ff000077").withScale(1);
		
		
		for (TaxiEdge te : edges) {
			String style = "#linestyle";
			if (te.getEdgeType() == TaxiEdge.EdgeType.TAXIWAY) {
				style += "Taxiway";
			} else if (te.getEdgeType() == TaxiEdge.EdgeType.RUNWAY) {
				style += "Runway";
			} else {
				style += "TaxiwayToGate";
			}
			
			LineString ls = documentTaxiwaysE.createAndAddPlacemark().withName("E" + at.getGMWTaxiEdgeID(te) + "-" + te.getUniqueString()).withStyleUrl(style)
			.createAndSetLineString();

			ls.addToCoordinates(te.getTnFrom().getLonCoordinate() + "," + te.getTnFrom().getLatCoordinate() + ",0");
			ls.addToCoordinates(te.getTnTo().getLonCoordinate() + "," + te.getTnTo().getLatCoordinate() + ",0");
		}
		
		for (TaxiNode tn : nodes) {
			Placemark p = documentTaxiwaysN.createAndAddPlacemark().withName(at.getGMWTaxiNodeID(tn) + "-" + tn.getId() + "-" + tn.getMeta()).withStyleUrl("#placemarkStyleGates").withVisibility(false);
			p.createAndSetPoint().addToCoordinates(tn.getLonCoordinate(), tn.getLatCoordinate());
		}

		// now add tracks for all flightpaths
		int num = 0;
		for (LatLng[][] flightpath : flightpaths) {
			String acName = "FP" + num + "-" + flights[num];
			final Document documentFlightpath = documentFlightpaths.createAndAddDocument().withName(acName).withOpen(false);
			
			LineString lsOriginalFP = documentFlightpath.createAndAddPlacemark().withName("Original"/*+acName*/).withStyleUrl("#linestyleOriginalFlightpath").withVisibility(false).createAndSetLineString();
			for (int i = 0; i < flightpath[0].length; i++) {
				lsOriginalFP.addToCoordinates(flightpath[0][i].getLng() + "," + flightpath[0][i].getLat() + ",0");
			}
			
			LineString lsDisplacedFP = documentFlightpath.createAndAddPlacemark().withName((flightpath[1].length==0?"Not":"")+"Displaced"/*+acName*/).withStyleUrl("#linestyleDisplacedFlightpath").withVisibility(false).createAndSetLineString();
			for (int i = 0; i < flightpath[1].length; i++) {
				lsDisplacedFP.addToCoordinates(flightpath[1][i].getLng() + "," + flightpath[1][i].getLat() + ",0");
			}
			
			for (int splitTrackNumber = 2; splitTrackNumber < flightpath.length; splitTrackNumber++) {
				Integer gmID = gmwIdsForACs.get(routes[num].get(splitTrackNumber-2));
				LineString lsSnappedFP = documentFlightpath.createAndAddPlacemark().withName("Snapped"+/*acName+"-"+*/("ABCDEFGHIJKLMNOPQRSTUVWXYZ").charAt(splitTrackNumber-2)+"-GM_ACID"+gmID+"-"+flights[num]).withStyleUrl("#linestyleSnappedFlightpath").withVisibility(false).createAndSetLineString();
				for (int i = 0; i < flightpath[splitTrackNumber].length; i++) {
					lsSnappedFP.addToCoordinates(flightpath[splitTrackNumber][i].getLng() + "," + flightpath[splitTrackNumber][i].getLat() + ",0");
				}
			}
			num++;
		}
		
		KMLUtils.addGroundOverlayToKMLDocument(filename, document);
		
		try {
			kml.marshal(new File(filename));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
