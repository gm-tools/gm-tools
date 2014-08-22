package gmtools.tools;

import gmtools.common.Geography;
import gmtools.common.GroundMovementWriter;
import gmtools.common.GroundMovementWriter.Aircraft;
import gmtools.common.GroundMovementWriter.Aircraft.Type;
import gmtools.common.GroundMovementWriter.Route;
import gmtools.common.KMLUtils;
import gmtools.common.Legal;
import gmtools.graph.TaxiEdge;
import gmtools.graph.TaxiNode;
import gmtools.graph.TaxiNode.NodeType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Icon;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.KmlFactory;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Style;

public class GM2KML {
	/**controls what is output*/
	private enum Mode {
		/**output taxiways,stands and runways only (omitNonStandNodes=false) */ STATIC, // implemented
		/**output all flight tracks*/ ALL_MOVEMENTS, // implemented
		/**output all flight tracks, in bins of 3 hours */ ALL_MOVEMENTS_BINNED, // implemented
		/**output paths used by aircraft to reach stands (omitNonStandNodes=true)*/ STAND_PATHS, // implemented
		/**output paths used by aircraft to reach stands in bins of 3 hours (omitNonStandNodes=true)*/ STAND_PATHS_BINNED, // implemented
		VISITS_PER_STAND, // implemented
		MOVEMENTS_PER_EDGE, // TODO
		AVERAGE_SPEED_PER_EDGE, // implemented
		AVERAGE_SPEED_PER_EDGE_BINNED // implemented
	}
	
	private static final String DETAIL_SEPARATOR = "\t";
	
	// usage: GM2KML inputGMFile [args]
	// args:
	// -o=filename : kml output filename (default=gm2kmlOutput.kml)
	// -m=mode : one of STATIC,ALL_MOVEMENTS,ALL_MOVEMENTS_BINNED,STAND_PATHS,STAND_PATHS_BINNED,VISITS_PER_STAND,MOVEMENTS_PER_EDGE,AVERAGE_SPEED_PER_EDGE,AVERAGE_SPEED_PER_EDGE_BINNED
	// -d=filename : specifies filename to write details of the processed movements to
	// -t=filename : specifies filename to read edge times from (required for speed related modes)
	// -bins=30,120,180 : overrides binterval; specifies time bins for the "binned" modes. Comma-separated, number of minutes from midnight for the start of each bin (one is automatically added from midnight to the first specified one). Example defines bins for 0000-0030,0030-0200,0200-0230 and 0230-0000 
	// -bweekend=y/n : separate time bins will be created for weekends and weekdays (default=n)
	// -binterval=480 : generate time bins of the specified length in minutes (example here will make bins for 0000-0800,0800-1600,1600-0000) (default=180 / 3hours)
	// -iso=n : for speed related modes, ignore speeds over the specified value in m/s (used to erroneous values caused by noise in the data) (default is no limit, but 40m/s is suggested)
	// modes:
	// STATIC : output taxiways,stands and runways only 
	// ALL_MOVEMENTS : output all flight tracks
	// ALL_MOVEMENTS_BINNED : output all flight tracks, in bins as specified
	// STAND_PATHS : output paths used by aircraft to reach stands
	// STAND_PATHS_BINNED : output paths used by aircraft to reach stands in bins as specified
	// VISITS_PER_STAND : show number of movements at each stand with various sizes of icon
	// AVERAGE_SPEED_PER_EDGE : use colour to show the average taxi speed on edges (red=slow, white=fast, scaled to range of speeds in data)
	// AVERAGE_SPEED_PER_EDGE_BINNED : average speeds on edges during the times specified by the time bins
	public static void main(String[] args) {
		//args = "MAN_GM_withFlights-20131105_090000-20131112_085900.txt -o=MAN_a.kml -m=STAND_PATHS_BINNED -bweekend=y -bins=380,640,960,1200".split("\\s+");
		
		String inGM = null;
		String outKML = "gm2kmlOutput.kml";
		Mode mode = Mode.STATIC;
		String outDetail = null;
		String inEdgeTimes = null;
		double ignoreSpeedsOver = Double.POSITIVE_INFINITY;
		int[] timeBins = null;
		int timeBinInterval = 3 * 60; // in minutes
		boolean timeBinsSeparateForWeekends = false;
		
		Legal.printLicence("GM2KML");
		if (args.length < 1) {
			printUsage();
			System.exit(1);
		} else {
			inGM = args[0];
		}
		
		for (int i = 1; i < args.length; i++) {
			String arg = args[i];
			String argLC = arg;
			if (argLC.startsWith("-o=")) {
				outKML = arg.substring(3);
			} else if (argLC.startsWith("-m=")) {
				mode = Mode.valueOf(arg.substring(3).toUpperCase());
			} else if (argLC.startsWith("-d=")) {
				outDetail = arg.substring(3);
			} else if (argLC.startsWith("-t=")) {
				inEdgeTimes = arg.substring(3);
			} else if (argLC.startsWith("-bins=")) { // time bins
				String s = arg.substring(6);
				String[] ss = s.split(",");
				timeBins = new int[ss.length];
				try {
					for (int j = 0; j < ss.length; j++) {
						timeBins[j] = Integer.parseInt(ss[j]);
					}
				} catch (NumberFormatException e) { System.err.println("Trouble parsing time bin values " + arg); }
			} else if (argLC.startsWith("-bweekend=")) { // time bins separate for weekends
				timeBinsSeparateForWeekends = argLC.substring(10).startsWith("y") || argLC.substring(10).startsWith("t"); 
			} else if (argLC.startsWith("-binterval=")) { // time bins
				try {
					timeBinInterval = Integer.parseInt(arg.substring(11));
				} catch (NumberFormatException e) { System.err.println("Trouble parsing time bin interval value " + arg); }
			} else if (argLC.startsWith("-iso=")) {
				try {
					ignoreSpeedsOver = Double.parseDouble(arg.substring(5));
				} catch (NumberFormatException e) { System.err.println("Trouble parsing iso value " + arg); }
			}
		}
		
		if (!(new File(inGM).exists())) {
			System.err.println("Couldn't find input file \"" + inGM + "\"");
			System.exit(1);
		}
		File of = new File(outKML).getParentFile();
		if ((of != null) && !(of.exists())) { // null means current dir, which should exist; if other dir specified, check it exists 
			System.err.println("Couldn't find output path \"" + outKML + "\"");
			System.exit(2);
		}
		if (outDetail != null) {
			File ofD = new File(outDetail).getParentFile();
			if ((ofD != null) && !(ofD.exists())) { // null means current dir, which should exist; if other dir specified, check it exists 
				System.err.println("Couldn't find output path \"" + outDetail + "\"");
				System.exit(3);
			}
		}
		
		PrintStream outDetailPS = null;
		if (outDetail != null) {
			try {
				outDetailPS = new PrintStream(new FileOutputStream(outDetail));
				
				if ((mode == Mode.VISITS_PER_STAND) || (mode == Mode.STAND_PATHS)) {
					outDetailPS.println(objectsToString("StandID","NumVisits"));
				} else if (mode == Mode.STAND_PATHS_BINNED) {
					outDetailPS.println(objectsToString("StandID","TimePeriod","NumVisits"));
				} else {
					outDetailPS.println("No details for mode " + mode);
				}
			} catch (IOException e) {
				System.err.println("Error setting up file \"" + outDetail + "\" for output");
			}
		}
		
		TimeBinManager timeBinManager = null;
		if ((mode == Mode.ALL_MOVEMENTS_BINNED) || (mode == Mode.AVERAGE_SPEED_PER_EDGE_BINNED) || (mode == Mode.STAND_PATHS_BINNED)) {
			if (timeBins != null) {
				timeBinManager = new TimeBinManager(timeBins, timeBinsSeparateForWeekends);
			} else {
				timeBinManager = new TimeBinManager(timeBinInterval, timeBinsSeparateForWeekends);
			}
		}
		
		System.out.println("Loading taxiways...");
		GroundMovementWriter gmw = new GroundMovementWriter(inGM);
		TaxiGen at = new TaxiGen(gmw);
		System.out.println("Loaded. Loading aircraft movements...");
		Object acs = null;
		if (mode == Mode.STAND_PATHS || mode == Mode.VISITS_PER_STAND || mode == Mode.ALL_MOVEMENTS) {
			acs = getAircraftRoutes(gmw, at, mode != Mode.ALL_MOVEMENTS);
		} else if (mode == Mode.STAND_PATHS_BINNED || mode == Mode.ALL_MOVEMENTS_BINNED) {
			acs = getAircraftRoutesInTimeBins(gmw, at, mode != Mode.ALL_MOVEMENTS_BINNED, timeBinManager);
		}
		Map<String, Map<TaxiEdge, List<Double>>> speeds = null;
		if ((mode == Mode.AVERAGE_SPEED_PER_EDGE) || (mode == Mode.AVERAGE_SPEED_PER_EDGE_BINNED)) {
			System.out.println("Loaded. Loading speeds...");
			speeds = readAndCalcEdgeSpeeds(at, inEdgeTimes, (mode == Mode.AVERAGE_SPEED_PER_EDGE_BINNED), ignoreSpeedsOver, timeBinManager);
		}
		System.out.println("Loaded. Writing to " + outKML + "...");
		graphNodesAndEdgesToKML(outKML, outDetailPS, at.getAllNodes().values(), at.getAllEdges(), acs, speeds, mode);
		System.out.println("All done.");
		
		if (outDetailPS != null) {
			outDetailPS.close();
		}
	}
	
	public static void printUsage() {
		System.out.println("Load a GM file, with possibly additional flight movement data, and write out a KML file for analysis");
		System.out.println("Usage: GM2KML inputGMFile [options]");
		System.out.println();
		System.out.println("Options:");
		System.out.println("-o=filename : kml output filename (default=gm2kmlOutput.kml)");
		System.out.println("-m=mode : one of STATIC,ALL_MOVEMENTS,ALL_MOVEMENTS_BINNED,STAND_PATHS,STAND_PATHS_BINNED,VISITS_PER_STAND,MOVEMENTS_PER_EDGE,AVERAGE_SPEED_PER_EDGE,AVERAGE_SPEED_PER_EDGE_BINNED");
		System.out.println("-d=filename : specifies filename to write details of the processed movements to");
		System.out.println("-t=filename : specifies filename to read edge times from (required for speed related modes)");
		System.out.println("-bins=30,120,180 : overrides binterval; specifies time bins for the \"binned\" modes. Comma-separated, number of minutes from midnight for the start of each bin (one is automatically added from midnight to the first specified one). Example defines bins for 0000-0030,0030-0200,0200-0230 and 0230-0000 ");
		System.out.println("-bweekend=y/n : separate time bins will be created for weekends and weekdays (default=n)");
		System.out.println("-binterval=480 : generate time bins of the specified length in minutes (example here will make bins for 0000-0800,0800-1600,1600-0000) (default=180 / 3hours)");
		System.out.println("-iso=n : for speed related modes, ignore speeds over the specified value in m/s (used to erroneous values caused by noise in the data) (default is no limit, but 40m/s is suggested)");
		System.out.println();
		System.out.println("Modes:");
		System.out.println("STATIC : output taxiways,stands and runways only ");
		System.out.println("ALL_MOVEMENTS : output all flight tracks");
		System.out.println("ALL_MOVEMENTS_BINNED : output all flight tracks, in bins as specified");
		System.out.println("STAND_PATHS : output paths used by aircraft to reach stands");
		System.out.println("STAND_PATHS_BINNED : output paths used by aircraft to reach stands in bins as specified");
		System.out.println("VISITS_PER_STAND : show number of movements at each stand with various sizes of icon");
		System.out.println("AVERAGE_SPEED_PER_EDGE : use colour to show the average taxi speed on edges (red=slow, white=fast, scaled to range of speeds in data)");
		System.out.println("AVERAGE_SPEED_PER_EDGE_BINNED : ");
		System.out.println();
	}
	
	/**get Map of arrays of flights, each flight being an array of edges visited, keyed by gate ID (option to not key by gate is present to maintain ordering if just loading all flights)*/
	private static Map<String, List<Movement>> getAircraftRoutes(GroundMovementWriter gmw, TaxiGen at, boolean keyByStand) {
		Map<String, List<Movement>> rval = new TreeMap<String, List<Movement>>(); // treemap for deterministic behaviour
		
		List<Aircraft> aircrafts = gmw.getAircraft();
		
		for (Aircraft aircraft : aircrafts) {
			// get routes
			int[] ids = aircraft.getRouteIDs();
			
			// for each route, get the appropriate edges
			for (int id : ids) {
				Route r = gmw.getRoute(id);
				int[] edgeIDs = r.getPath();
				String standID = null;
				List<TaxiEdge> edges = new ArrayList<TaxiEdge>(edgeIDs.length);
				for (int i = 0; i < edgeIDs.length; i++) {
					TaxiEdge te = at.getEdgeByGMWId(edgeIDs[i]);
					edges.add(te);
					
					String sn = te.getStandName();
					if (sn != null) {
						standID = sn;
					}
				}
				
				if (standID == null) { // don't bother if it's an invalid movement (for us, this means no stand)
					System.out.println("Warning: no stand for flight " + aircraft + " on route " + id);
				} else {
					if (!keyByStand) {
						standID = "ALL";
					}
				
					List<Movement> currentRoutesForStand = rval.get(standID);
					if (currentRoutesForStand == null) {
						currentRoutesForStand = new ArrayList<Movement>();
						rval.put(standID, currentRoutesForStand);
					}
					currentRoutesForStand.add(new Movement(aircraft, edges));
				}
			}
		}
		
		return rval;
	}
	
	/**get a Map of (Maps of arrays of flights, each flight being an array of edges visited, keyed by date/time), keyed by gate ID*/
	private static Map<String, Map<String, List<Movement>>> getAircraftRoutesInTimeBins(GroundMovementWriter gmw, TaxiGen at, boolean keyByStand, TimeBinManager timeBinManager) {
		Map<String, Map<String, List<Movement>>> rval = new HashMap<String, Map<String, List<Movement>>>();
		
		List<Aircraft> aircrafts = gmw.getAircraft();
		
		for (Aircraft aircraft : aircrafts) {
			// get routes
			int[] ids = aircraft.getRouteIDs();
			
			// get timing for AC
			long time;
			if (aircraft.getType() == Type.departure) {
				time = aircraft.getStartTime()[1];
			} else { // arrival/towing
				time = aircraft.getEndTime()[1];
			}
			
			// use that to figure out the bin name - format HH-HH-[we/wd] (weekend/weekday)
			String timeString = timeBinManager.getTimeBinNameForTime(new Date(time));
			
			// for each route, get the appropriate edges
			for (int id : ids) {
				Route r = gmw.getRoute(id);
				int[] edgeIDs = r.getPath();
				String standID = null;
				List<TaxiEdge> edges = new ArrayList<TaxiEdge>(edgeIDs.length);
				for (int i = 0; i < edgeIDs.length; i++) {
					TaxiEdge te = at.getEdgeByGMWId(edgeIDs[i]);
					edges.add(te);
					
					String sn = te.getStandName();
					if (sn != null) {
						standID = sn;
					}
				}
				
				if (standID == null) { // don't bother if it's an invalid movement (for us, this means no stand)
					System.out.println("Warning: no stand for flight " + aircraft + " on route " + id);
				} else {
					if (!keyByStand) {
						standID = "ALL";
					}
				
					Map<String, List<Movement>> currentRoutesForStand = rval.get(standID);
					if (currentRoutesForStand == null) {
						currentRoutesForStand = new TreeMap<String, List<Movement>>();
						rval.put(standID, currentRoutesForStand);
					}
					List<Movement> currentRoutesForStandAtTime = currentRoutesForStand.get(timeString);
					if (currentRoutesForStandAtTime == null) {
						currentRoutesForStandAtTime = new ArrayList<Movement>();
						currentRoutesForStand.put(timeString, currentRoutesForStandAtTime);
					}
					currentRoutesForStandAtTime.add(new Movement(aircraft, edges));
				}
			}
		}
		
		return rval;
	}

	// set flights to null to skip them
    @SuppressWarnings("unchecked")
	private static void graphNodesAndEdgesToKML(String filename, PrintStream outDetail, Collection<TaxiNode> nodes, Collection<TaxiEdge> edges, Object flightpaths, Map<String,Map<TaxiEdge,List<Double>>> speeds, Mode mode) {
    	String filePrefix = filename.substring(0, filename.lastIndexOf('.'));
    	
    	boolean addingFlightTracks = false; // tracks all in one group
    	boolean addingFlightTracksToStands = false; // tracks in groups associated with stands
    	boolean hidingTaxiways = false;
    	boolean omitNonStandNodes = false;
    	boolean flightTracksBinned = false;
    	boolean edgeSpeeds = false;
    	@SuppressWarnings("unused")
		boolean speedsBinned = false;
    	boolean taxiwaysThin = false; // if varying the colour of taxiways to reflect traffic/speeds, set to true to make default taxiway just thin black
    	switch (mode) {
    		case STAND_PATHS:
    			omitNonStandNodes = true;
    			addingFlightTracksToStands = (flightpaths != null);
    			hidingTaxiways = addingFlightTracksToStands;
    			break;
    		case STAND_PATHS_BINNED:
    			omitNonStandNodes = true;
    			addingFlightTracksToStands = (flightpaths != null);
    			hidingTaxiways = addingFlightTracksToStands;
    			flightTracksBinned = true;
    			break;
    		case ALL_MOVEMENTS:
    			omitNonStandNodes = true;
    			addingFlightTracks = (flightpaths != null);
    			break;
    		case ALL_MOVEMENTS_BINNED:
    			omitNonStandNodes = true;
    			flightTracksBinned = true;
    			addingFlightTracks = (flightpaths != null);
    			break;
    		case AVERAGE_SPEED_PER_EDGE:
    			omitNonStandNodes = true;
    			flightTracksBinned = true;
    			addingFlightTracks = (flightpaths != null);
    			taxiwaysThin = true;
    			edgeSpeeds = true;
    			break;	
    		case AVERAGE_SPEED_PER_EDGE_BINNED:
    			omitNonStandNodes = true;
    			flightTracksBinned = true;
    			addingFlightTracks = (flightpaths != null);
    			taxiwaysThin = true;
    			edgeSpeeds = true;
    			speedsBinned = true;
    			hidingTaxiways = true; // hide until set visible by user
    			break;	
    		default:
				// do nothing, default values are above
    			break;
    	}
    	
		final Kml kml = KmlFactory.createKml();
		final Document document = kml.createAndSetDocument().withName(filePrefix).withOpen(true);
		final Document documentTaxiwaysE = document.createAndAddDocument().withName(filePrefix + "TaxiwaysEdges").withOpen(!addingFlightTracksToStands).withVisibility(!addingFlightTracksToStands); // make edges invisible and collapsed if looking at flight tracks 
		final Document documentTaxiwaysN = document.createAndAddDocument().withName(filePrefix + "TaxiwaysNodes").withOpen(true);
		final Style styleOSM = documentTaxiwaysN.createAndAddStyle().withId("placemarkStyleGates");
		styleOSM.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor("ffff7777").withScale(1);

		// normally taxiways are red and weight=4, but if they have varying speed then we'll make them thin and colourless
		final Style styleTaxiway = documentTaxiwaysE.createAndAddStyle().withId("linestyleTaxiway");
		if (taxiwaysThin) {
			styleTaxiway.createAndSetLineStyle()
			.withColor("ffffffff")
			.withWidth(1.0);
		} else {
			styleTaxiway.createAndSetLineStyle()
			.withColor("550000ff")
			.withWidth(4.0);
		}

		final Style styleTaxiwayToGate = documentTaxiwaysE.createAndAddStyle().withId("linestyleTaxiwayToGate");
		styleTaxiwayToGate.createAndSetLineStyle()
		.withColor("55ff0000")
		.withWidth(4.0);
		
		final Style styleRunway = documentTaxiwaysE.createAndAddStyle().withId("linestyleRunway");
		styleRunway.createAndSetLineStyle()
		.withColor("5500ff00")
		.withWidth(4.0);

		final Style styleOriginalFP = document.createAndAddStyle().withId("linestyleFlightpath");
		styleOriginalFP.createAndSetLineStyle()
		.withColor("2255ee00")
		.withWidth(4.0);
		
		// set up styles for varying stand sizes
		int numberOfStandSizes = 10;
		int maxNumberAtOneStand = 0;
		if (mode == Mode.VISITS_PER_STAND) {
			for (int i = 0; i < numberOfStandSizes; i++) {
				final Style styleOSMSized = documentTaxiwaysN.createAndAddStyle().withId("placemarkStyleGates-"+i);
				styleOSMSized.createAndSetIconStyle().withIcon(new Icon().withHref("http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png")).withColor("ffff7777").withScale((i+1)/2.0);
			}
			if (flightpaths != null) {
				for (List<Movement> l : ((Map<String, List<Movement>>)flightpaths).values()) {
					maxNumberAtOneStand = Math.max(maxNumberAtOneStand, l.size());
				}
			}
		}
		
		// set up styles for differently weighted edges
		int numberOfEdgeGroups = 10; // for both traffic and speeds (traffic currently handled separately)
		double maxSpeed = 0;
		int maxTraffic = 0;
		if (edgeSpeeds) { // speeds go from red (0) to white (max)
			for (int i = 0; i < numberOfEdgeGroups; i++) {
				String s = Integer.toHexString((int)(i * 255.0 / (numberOfEdgeGroups - 1)));
				if (s.length() < 2) {
					s = "0" + s;
				}
			
				final Style styleTaxiwaySpeed = documentTaxiwaysE.createAndAddStyle().withId("linestyleTaxiway-" + i);
				styleTaxiwaySpeed.createAndSetLineStyle()
				.withColor("ff"+s+s+"ff")
				.withWidth(4.0);
			}
			if (speeds != null) {
				for (Map<TaxiEdge, List<Double>> m : speeds.values()) {
					for (List<Double> l : m.values()) {
						for (Double d : l) {
							maxSpeed = Math.max(maxSpeed, d);
						}
						maxTraffic = Math.max(maxTraffic, l.size());
					}
				}
			}
		}

		// render all the edges, possibly coloured according to average speeds
		// the speeds variable will be non-null if speeds have been calc'd
		// where they haven't it'll be null, but we still need to run this at least once
		// so we run either once, or use the iterator on the speeds entrySet
		boolean edgesRenderedAtLeastOnce = false;
		Iterator<Entry<String, Map<TaxiEdge,List<Double>>>> speedsIterator = (speeds != null) ? speeds.entrySet().iterator() : null;
		while ((!edgeSpeeds && !edgesRenderedAtLeastOnce) || ((speedsIterator == null) && !edgesRenderedAtLeastOnce) || ((speedsIterator != null) && speedsIterator.hasNext())) {
			Document documentToRenderEdgesIn = documentTaxiwaysE;
			Entry<String, Map<TaxiEdge,List<Double>>> e = null;
			String binName = null;
			if (speedsIterator != null) {
				e = speedsIterator.next();
				binName = e.getKey();
				documentToRenderEdgesIn = documentTaxiwaysE.createAndAddDocument().withName("EdgeSpeeds@ " + binName).withOpen(false).withVisibility(false);
			}
			
			for (TaxiEdge te : edges) {
				String edgeName = "E" + te.getId();
				String style = "#linestyle";
				if (te.getEdgeType() == TaxiEdge.EdgeType.TAXIWAY) {
					if (edgeSpeeds) {
						List<Double> speedsForThisEdge;
						speedsForThisEdge = e.getValue().get(te); // e.getValue() is current speeds bin (covering either a time range, or "ALL")
						if (speedsForThisEdge != null) {
							// calc average
							double total = 0;
							for (double d : speedsForThisEdge) {
								total += d;
							}
							double average = total / speedsForThisEdge.size();
							double fractional = average / maxSpeed;
							int bin = (int)(fractional * numberOfEdgeGroups);
							style += "Taxiway-"+bin;
							double rounded = gmtools.common.Maths.roundDouble(average, 2);
							edgeName += " av=" + rounded;

							if (outDetail != null) {
								outDetail.println(objectsToString(te.toString(), average, bin));
							}
						} else {
							style += "Taxiway";
							edgeName += " av=" + "no traffic";
						}
					} else {
						style += "Taxiway";
					}
				} else if (te.getEdgeType() == TaxiEdge.EdgeType.RUNWAY) {
					edgeName += "-" + te.getMeta();
					style += "Runway";
				} else {
					String standName = te.getStandName();
					if (standName != null) {
						edgeName += "-" + te.getMeta();
					}
					style += "TaxiwayToGate";
				}
				
				LineString ls = documentToRenderEdgesIn.createAndAddPlacemark().withName(edgeName).withStyleUrl(style).withVisibility(!hidingTaxiways).createAndSetLineString();
				ls.addToCoordinates(te.getTnFrom().getLonCoordinate() + "," + te.getTnFrom().getLatCoordinate() + ",0");
				ls.addToCoordinates(te.getTnTo().getLonCoordinate() + "," + te.getTnTo().getLatCoordinate() + ",0");
			} // end of loop over edges
			
			edgesRenderedAtLeastOnce = true;
		} // end of loop over bins of edges
		
		for (TaxiNode tn : nodes) {
			if (!omitNonStandNodes || (tn.getNodeType() == NodeType.STAND)) {
				String size = "";
				int flightCount = 0;
				if (mode == Mode.VISITS_PER_STAND) {
					if (tn.getNodeType() == NodeType.STAND) {
						List<Movement> visits = ((Map<String, List<Movement>>)flightpaths).get(tn.getMeta());
						if (visits != null) {
							flightCount = visits.size();
							size = "-" + Math.min(numberOfStandSizes - 1, (1 + (int)(flightCount / ((double)maxNumberAtOneStand / numberOfStandSizes))));
							
							if (outDetail != null) {
								outDetail.println(objectsToString(tn.getId(), flightCount));
							}
						} else {
							size = "-0";
						}
					}
				}
				
				Document documentToAddStandTo = documentTaxiwaysN;
				if (addingFlightTracksToStands) {
					documentToAddStandTo = documentTaxiwaysN.createAndAddDocument().withName(tn.getId()).withOpen(false).withVisibility(true);
				}
				
				Placemark p = documentToAddStandTo.createAndAddPlacemark().withName(tn.getId()).withStyleUrl("#placemarkStyleGates"+size);
				p.createAndSetPoint().addToCoordinates(tn.getLonCoordinate(), tn.getLatCoordinate());
				
				// for each gate, add tracks for all visiting aircraft
				if (tn.getNodeType() == NodeType.STAND) {
					if (addingFlightTracksToStands) {
						Object tracks = ((Map<String, ?>)flightpaths).get(tn.getMeta());
						int trackNum = 0;
						if (tracks != null) { // not all stands have tracks
							if (flightTracksBinned) {
								for (Entry<String, List<Movement>> e : ((Map<String, List<Movement>>)tracks).entrySet()) {
									final Document documentStand = documentToAddStandTo.createAndAddDocument().withName("Flts@ " + tn.getId() + "," + e.getKey()).withOpen(false).withVisibility(false);
									for (Movement m : e.getValue()) {
										List<TaxiNode> trackNodes = edgeListToNodeList(m.getRoute());
										renderFlightPath(documentStand, "Flight"+m.getAircraft().getSeqNo(), "#linestyleFlightpath", false, trackNodes);
										trackNum++;
									}
									
									if (outDetail != null) {
										outDetail.println(objectsToString(tn.getId(), e.getKey(), trackNum));
									}
								}
							} else {
								final Document documentStand = documentToAddStandTo.createAndAddDocument().withName("FlightsAtStand " + tn.getId()).withOpen(false).withVisibility(false);
								for (Movement m : (List<Movement>)tracks) {
									List<TaxiNode> trackNodes = edgeListToNodeList(m.getRoute());
									renderFlightPath(documentStand, "Flight"+m.getAircraft().getSeqNo(), "#linestyleFlightpath", false, trackNodes);
									trackNum++;
								}
								
								if (outDetail != null) {
									outDetail.println(objectsToString(tn.getId(), trackNum));
								}
							}
							
							flightCount = trackNum;
						}
					}
					
					if (mode == Mode.VISITS_PER_STAND || mode == Mode.STAND_PATHS || mode == Mode.STAND_PATHS_BINNED) {
						p.setName(p.getName() + ", " + flightCount + " flights");
					}
				}
			}
		}

		// now add tracks for all flightpaths (just use the tracks in the stands map, but ignore the stand keys)
		if (addingFlightTracks) {
			@SuppressWarnings("unused")
			int trackNum = 0;
			
			if (flightTracksBinned) {
				// first find all the bins (needs done here because the data is structures per stand)
				Map<String,Document> binnedDocuments = new TreeMap<String,Document>();
				for (Object tracks : ((Map<String, ?>)flightpaths).values()) {
					for (String s : ((Map<String, List<Movement>>)tracks).keySet()) {
						if (!binnedDocuments.containsKey(s)) {
							final Document documentStand = document.createAndAddDocument().withName("Flts@ " + s).withOpen(false).withVisibility(false);
							binnedDocuments.put(s, documentStand);
						}
					}
				}
				
				for (Object tracks : ((Map<String, ?>)flightpaths).values()) {
					for (Entry<String, List<Movement>> e : ((Map<String, List<Movement>>)tracks).entrySet()) {
						for (Movement m : e.getValue()) {
							List<TaxiNode> trackNodes = edgeListToNodeList(m.getRoute());
							renderFlightPath(binnedDocuments.get(e.getKey()), "Flight"+m.getAircraft(), "#linestyleFlightpath", false, trackNodes);
							trackNum++;
						}
					}
				}
			} else {
				final Document documentStand = document.createAndAddDocument().withName("All Flights").withOpen(false).withVisibility(true);
				for (Object tracks : ((Map<String, ?>)flightpaths).values()) {
					for (Movement m : (List<Movement>)tracks) {
						List<TaxiNode> trackNodes = edgeListToNodeList(m.getRoute());
						renderFlightPath(documentStand, "Flight"+m.getAircraft().getSeqNo(), "#linestyleFlightpath", false, trackNodes);
						trackNum++;
					}
				}
			}
		}
		
		KMLUtils.addGroundOverlayToKMLDocument(filename, document);
		
		try {
			kml.marshal(new File(filename));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
    
    /**
     * compute speeds for all traversals of each edge; this will also allow for reporting numbers of flights on each edge
     * @return map indexed by times, elements are maps of edgeID to lists of traversal times
     */
    private static Map<String, Map<TaxiEdge,List<Double>>> readAndCalcEdgeSpeeds(TaxiGen at, String inEdgeTimes, boolean binning, double ignoreSpeedsOver, TimeBinManager timeBinManager) {
    	Map<String, Map<TaxiEdge,List<Double>>> rval = new TreeMap<String, Map<TaxiEdge,List<Double>>>();
    	Map<String, PrintStream> binnedEdgeTimes = new TreeMap<String, PrintStream>();
    	
		try {
		    BufferedReader in = new BufferedReader(new FileReader(inEdgeTimes));
		    final String SEPARATOR = ",";
		    
		    String header = in.readLine(); // get header
		    String[] cols = header.split(SEPARATOR);
		    int indexEdgeID = -1, indexEdgeInTime = -1, indexEdgeOutTime = -1, indexTime = -1;
		    for (int i = 0; i < cols.length; i++) {
				if (cols[i].equals("EdgeID")) {
					indexEdgeID = i;
				} else if (cols[i].equals("EdgeInTime")) {
					indexEdgeInTime = i;
				} else if (cols[i].equals("EdgeOutTime")) {
					indexEdgeOutTime = i;
				} else if (cols[i].equals("TimeTakenSeconds")) {
					indexTime = i;
				}
			}
		    
		    if ((indexEdgeID < 0) || (indexEdgeInTime < 0) || (indexEdgeOutTime < 0) || (indexTime < 0)) {
		    	in.close();
		    	throw new IOException("Bad file format. Couldn't find column(s) headed" + (indexEdgeID<0?":EdgeID":"") + (indexEdgeInTime<0?":EdgeInTime":"") + (indexEdgeOutTime<0?":EdgeOutTime":"") + (indexTime<0?":TimeTakenSeconds":""));
		    }
		    
		    String line;
		    while ((line = in.readLine()) != null) {
		    	cols = line.split(SEPARATOR);
		    	
		    	int edgeID = Integer.parseInt(cols[indexEdgeID]);
		    	double edgeTime = Double.parseDouble(cols[indexTime]);
		    	boolean keepingThisLine = true;
		    	long arrivalTime = -1;
		    	try {
		    		if (binning) {
		    			arrivalTime = Long.parseLong(cols[indexEdgeInTime]);
		    		} else {
		    			arrivalTime = 0; // if not binning, just make all the arrival times the same
		    		}
		    	} catch (NumberFormatException e) { // problem reading number? try to use exit time for binning instead, otherwise, leave as -1
		    		try {
		    			arrivalTime = Long.parseLong(cols[indexEdgeOutTime]);
		    		} catch (NumberFormatException e2) {}
		    	}
		    	
		    	String timeString = "ALL";
		    	if (arrivalTime < 0) {
		    		keepingThisLine = false;
		    	}
		    	
		    	if (binning) {
		    		if (keepingThisLine) { // that is, we managed to figure out which bin to put this edge time into
				    	// figure out the bin name - format HH-HH-[we/wd] (weekend/weekday)
						// basically need to round the time; figure out if weekend, then set time
						timeString = timeBinManager.getTimeBinNameForTime(new Date(arrivalTime));
		    			
						// write out edge time to appropriate bin
						PrintStream binnedOut = binnedEdgeTimes.get(timeString);
						if (binnedOut == null) {
							binnedOut = new PrintStream(new FileOutputStream(inEdgeTimes + "_" + timeString + ".txt"));
							binnedOut.println(header);
							binnedEdgeTimes.put(timeString, binnedOut);
						}
						binnedOut.println(line);
		    		}
				}
			
		    	if (keepingThisLine) {
					Map<TaxiEdge, List<Double>> speedsForThisTimePeriod = rval.get(timeString);
					if (speedsForThisTimePeriod == null) {
						speedsForThisTimePeriod = new TreeMap<TaxiEdge, List<Double>>();
						rval.put(timeString, speedsForThisTimePeriod);
					}
					
					TaxiEdge te = at.getEdgeByGMWId(edgeID);
					if (te == null) {
						System.err.println("Couldn't find edge ID " + edgeID);
					}
					
					double speed = te.getLength() / edgeTime;
					if (speed > ignoreSpeedsOver) {
						speed = ignoreSpeedsOver;
					}
					
					if (!Double.isNaN(speed)) {
						List<Double> speedsForThisEdge = speedsForThisTimePeriod.get(te);
						if (speedsForThisEdge == null) {
							speedsForThisEdge = new ArrayList<Double>();
							speedsForThisTimePeriod.put(te, speedsForThisEdge);
						}
						
						speedsForThisEdge.add(speed);
					}
		    	}
		    } // end of loop over edge times file
		    
		    in.close();
		    
		    // close binned output files
		    for (PrintStream ps : binnedEdgeTimes.values()) {
		    	ps.close();
		    }
		} catch (IOException e) {
			System.err.println("Error reading from edge times file " + inEdgeTimes + ": " + e.toString());
			System.exit(1);
		}

    	return rval;
    }
    
    private static void renderFlightPath(Document documentToAddTo, String nameOfTrack, String styleUrl, boolean visible, List<TaxiNode> trackNodes) {
		LineString ls2 = documentToAddTo.createAndAddPlacemark().withName(nameOfTrack).withStyleUrl(styleUrl).withVisibility(visible) // default is for the tracks to be hidden 
		.createAndSetLineString();

		for (int i = 1; i < trackNodes.size(); i++) {
			ls2.addToCoordinates(trackNodes.get(i - 1).getLonCoordinate() + "," + trackNodes.get(i - 1).getLatCoordinate() + ",0");
			ls2.addToCoordinates(trackNodes.get(i).getLonCoordinate() + "," + trackNodes.get(i).getLatCoordinate() + ",0");
		}
    }
    
    public static List<TaxiNode> edgeListToNodeList(List<TaxiEdge> edges) {
		if (edges.size() == 1) {
			return Arrays.asList(new TaxiNode[]{edges.get(0).getTnFrom(), edges.get(0).getTnTo()});
		}
		
		List<TaxiNode> nodes = new ArrayList<TaxiNode>();
		@SuppressWarnings("unused")
		boolean previousFromTo;
		
		// first, find the start node - the one which isn't present in the second edge.
		// for each edge, look at the two nodes, and work out which was present in the previous node. Add the other node and move on.
		TaxiNode previousNode;
		TaxiEdge previousEdge = edges.get(0);
		if ((previousEdge.getTnFrom() != edges.get(1).getTnFrom()) && (previousEdge.getTnFrom() != edges.get(1).getTnTo())) {
			previousNode = previousEdge.getTnFrom();
			previousFromTo = true;
		} else {
			previousNode = previousEdge.getTnTo();
			previousFromTo = false;
		}
		nodes.add(previousNode);

		for (int i = 0; i < edges.size(); i++) {
			TaxiEdge currentEdge = edges.get(i);
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
			}
			
			nodes.add(currentNode);
			previousEdge = currentEdge;
			previousNode = currentNode;
			previousFromTo = currentFromTo;
		}
		
		return nodes;
	}
    
    private static int roundMDownToNearestN(int m, int n) {
    	if (m > 0)
            return (int)(Math.floor((double)m / n) * n);
        else if (m < 0)
            return (int)(Math.ceil((double)m / n) * n);
        else
            return n;
    }
    
    private static String objectsToString(Object... array) {
        StringBuffer rval = new StringBuffer();
     
        boolean first = true;
        for (int i = 0; i < array.length; i++) {
        	if (first) {
        		first = false;
        	} else {
        		rval.append(DETAIL_SEPARATOR);
        	}
        	
            rval.append(array[i].toString());
        }
        
        return rval.toString();
    }
    
    private static class Movement {
    	private Aircraft aircraft;
    	private List<TaxiEdge> route;
    	
    	public Movement(Aircraft aircraft, List<TaxiEdge> route) {
    		this.aircraft = aircraft;
    		this.route = route;
    	}
    	
    	public Aircraft getAircraft() {
			return aircraft;
		}
    	
    	public List<TaxiEdge> getRoute() {
			return route;
		}
    }
    
    /**
     * utility to convert times into bins
     * for now, bins are quite simple: they have a start+end time (measured in minutes since midnight), and can be weekday or weekend
     * sometime we'll do some nicer pattern matching to do more clever stuff
     * 
     * essentially this just rounds dates to the time bin labels
     */
    private static class TimeBinManager {
    	private boolean distinguishWeekdaysWeekends;
    	private int intervalLengthInMinutes;
    	private boolean intervalIsWholeHours;
    	private List<TimeBin> timeBins;
    	private static final String NAME_UNBINNED = "UNBINNED";
    	
    	public TimeBinManager(int intervalLengthInMinutes, boolean distinguishWeekdaysWeekends) {
    		this.distinguishWeekdaysWeekends = distinguishWeekdaysWeekends;
    		this.intervalIsWholeHours = ((intervalLengthInMinutes % 60) == 0);
    		this.intervalLengthInMinutes = intervalLengthInMinutes;
    		
    		// if we can't keep to the simple method (whole hours, rounding down) we need to generate a set of bins covering the whole day 
    		if (!this.intervalIsWholeHours) {
    			this.timeBins = new ArrayList<TimeBin>();
    			for (int min = 0; min < (24 * 60); min+=intervalLengthInMinutes) {
    				this.timeBins.add(new TimeBin(min, Math.min(min+intervalLengthInMinutes, (24 * 60))));
    			}
    		}
    	}
    	
    	/**user-defined list of time bins - note: there is no checking for overlaps or gaps!*/
    	public TimeBinManager(int[] intervalStartTimesInMinutes, boolean distinguishWeekdaysWeekends) {
    		this.distinguishWeekdaysWeekends = distinguishWeekdaysWeekends;
    		this.intervalIsWholeHours = false;
    		//this.intervalLengthInMinutes = -1; // leave undefined so we get an exception if this is used
    		
    		this.timeBins = new ArrayList<TimeBin>();
    		
    		// add a bin at the start of the day if necessary
    		if (intervalStartTimesInMinutes[0] > 0) {
    			this.timeBins.add(new TimeBin(0, intervalStartTimesInMinutes[0]));
    		}
    		
			for (int i = 0; i < intervalStartTimesInMinutes.length; i++) {
				this.timeBins.add(new TimeBin(intervalStartTimesInMinutes[i], ((i < intervalStartTimesInMinutes.length - 1) ? intervalStartTimesInMinutes[i + 1] : (24 * 60))));
			}
    	}
    	
    	@SuppressWarnings("unused")
		public String[] getAllBinNames() {
    		String[] rval = new String[((this.distinguishWeekdaysWeekends ? 2 : 1) * this.timeBins.size()) + 1]; // one for each bin, x2 if binning for weekends too, +1 for UNBINNED
    		int i = 0;
    		for (TimeBin tb : this.timeBins) {
    			if (this.distinguishWeekdaysWeekends) {
    				rval[i++] = "wd-" + tb.name;
    				rval[i++] = "we-" + tb.name;
    			} else {
    				rval[i++] = tb.name;
    			}
    		}
    		
    		rval[i++] = NAME_UNBINNED;
    		
    		return rval;
    	}
    	
    	public String getTimeBinNameForTime(Date d) {
    		String timeString;
    		Calendar c = Calendar.getInstance();
			c.setTime(d);
			int day = c.get(Calendar.DAY_OF_WEEK);
			boolean weekend = (day == Calendar.SATURDAY || day == Calendar.SUNDAY);
    		if (this.intervalIsWholeHours) {
    			int hour = roundMDownToNearestN(c.get(Calendar.HOUR_OF_DAY), (this.intervalLengthInMinutes / 60));
    			timeString = (this.distinguishWeekdaysWeekends ? (weekend ? "we-" : "wd-") : "") + String.format("%02d", hour) + "-" + String.format("%02d", (hour + (this.intervalLengthInMinutes / 60)));
    		} else {
    			TimeBin tb = null;
    			int timeInMinutes = (c.get(Calendar.HOUR_OF_DAY) * 60) + c.get(Calendar.MINUTE);
    			for (int i = 0; (i < this.timeBins.size()) && (tb == null); i++) {
    				if (this.timeBins.get(i).contains(timeInMinutes)) {
    					tb = this.timeBins.get(i);
    				}
    			}
    			
    			if (tb != null) {
    				timeString = (this.distinguishWeekdaysWeekends ? (weekend ? "we-" : "wd-") : "") + tb.getName();
    			} else {
    				timeString = NAME_UNBINNED;
    			}
    		}
    		
    		return timeString;
    	}
    	
    	private class TimeBin {
    		private String name;
    		private int startTimeInclusive;
    		private int endTimeExclusive;
    		
    		private TimeBin(int startTimeInclusive, int endTimeExclusive) {
    			this.startTimeInclusive = startTimeInclusive;
    			this.endTimeExclusive = endTimeExclusive;
    			
    			this.name = String.format("%02d", (startTimeInclusive / 60)) + ":" + String.format("%02d", (startTimeInclusive % 60)) + "-" + String.format("%02d", (endTimeExclusive / 60)) + ":" + String.format("%02d", (endTimeExclusive % 60));
    		}
    		
    		public String getName() {
				return name;
			}
    		
    		public boolean contains(int timeInMinutes) {
    			return (timeInMinutes >= this.startTimeInclusive) && (timeInMinutes < this.endTimeExclusive);
    		}
    	}
    }
}
