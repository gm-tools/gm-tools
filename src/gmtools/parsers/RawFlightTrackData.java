package gmtools.parsers;

import gmtools.common.ArrayTools;
import gmtools.tools.SnapTracks;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import uk.me.jstott.jcoord.LatLng;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 *
 * <br/><br/>
 * This class wraps up the raw flight track data
 * This code supports reading multiple datasets, possibly overlapping in time
 */
public class RawFlightTrackData {
	public static final String HEADER_ID = "id";
	public static final String HEADER_ORIGIN = "origin";
	public static final String HEADER_DESTINATION = "destination";
	public static final String HEADER_TRACK = "track";
	public static final String HEADER_FIRSTTIMESTAMP = "firsttimestamp";
	
	public static final String SEPARATOR = "\t";
	public static final String SEPARATOR_COORDS = ",";
	
	private static boolean isCoordsNearAirport(double lat, double lon, double latAirport, double lonAirport, double airportRadius) {
		return (Math.abs(lat - latAirport) < airportRadius) && (Math.abs(lon - lonAirport) < airportRadius);
	}
	
	/**
	 * @param scraped - true if the input files are in the format coming direct from scraping, false if it's come from the snapping code
	 * @param basedir - base dir for input
	 * @param inFiles - filenames to read
	 * @param latAirport
	 * @param lonAirport
	 * @param airportRadius - for filtering
	 * @param airportID
	 * @param breakTracksIfGapOverS - max gap between TimeCoordinates (number of seconds); if over this, the track will be split
	 * @param min - if track has fewer points than this near the airport, it will be discarded
	 * @return
	 */
	public static List<Aircraft> loadAircraft(boolean snapped, boolean includesIntervals, String basedir, String[] inFiles, double latAirport, double lonAirport, double airportRadius, String airportID, long breakTracksIfGapOverS, int min) {
		int pathIncrement = includesIntervals ? 4 : 3;
		
		// somewhere to store coordinates
		List<Aircraft> aircrafts = new ArrayList<Aircraft>();
		
		// keep track of aircraft loaded from different files
		List<String> ids = new ArrayList<String>();
		
		int countRawTracks = 0;
		int countDroppedBecauseInMultipleFiles = 0;
		int countNoTracks = 0;
		int countEmptyProcessedTracks = 0;
		int countEmptyRawTracks = 0;
		int countSplitTracks = 0;
		int countSingleValid = 0;
		int countMultipleValid = 0;
		int countMultipleValidTotal = 0;
		
		int lastSize = 0;
		
		// read file
		for (String inFile : inFiles) {
			try {
				// try to open file
				BufferedReader in = new BufferedReader(new FileReader(basedir + inFile));
	
				// get header
				String[] header = in.readLine().split(SEPARATOR);
				ColumnIndices columnIndices = new ColumnIndices(header, basedir + inFile);
				
				String line;
				while ((line = in.readLine()) != null) {
					countRawTracks++;
					
					String[] cols = line.split(SEPARATOR);
										
					int idIndex = columnIndices.getColumnIndex(HEADER_ID, true);
					String id = (idIndex < cols.length) ? cols[idIndex] : "";
	
					int pathIndex = columnIndices.getColumnIndex(HEADER_TRACK, true);
					
					Integer firstTimestampIndex = columnIndices.getColumnIndex(HEADER_FIRSTTIMESTAMP, true);
					Integer destinationIndex = columnIndices.getColumnIndex(HEADER_DESTINATION, true);
					Integer originIndex = columnIndices.getColumnIndex(HEADER_ORIGIN, true);
					
					String destination = "?";
					if ((destinationIndex != null) && (destinationIndex < cols.length)) {
						destination = cols[destinationIndex];
					}
					String origin = "?";
					if ((originIndex != null) && (originIndex < cols.length)) {
						origin = cols[originIndex];
					}
					
					// coords are in a comma separated list - split again
					if ((cols.length > Math.max(pathIndex, firstTimestampIndex)) && (cols[pathIndex].contains(SEPARATOR_COORDS))) { // check there's enough columns, and that the path has some subseparators (so it might contain some coords)
						String[] s = cols[pathIndex].split(SEPARATOR_COORDS);

						// The destination/origin codes aren't always right! so just figure out inbound / outbound from the route data
						// The route data is usually backwards in the raw data
						// This still will be tripped up if an aircraft, in one route, leaves airport, goes elsewhere, then returns!
						boolean outbound = false;
						boolean inbound = false;
						boolean forwards = false;
						boolean found = false;
						
						long startDate = -1;
						try {
							startDate = Long.parseLong(cols[firstTimestampIndex]);
						} catch (NumberFormatException e) {}
						
						if (snapped) { // if snapped, assume that all flights are at correct airport on the ground: just copy coords (they were forwards) and get origin/destination from file
							outbound = cols[originIndex].equals(airportID);
							inbound = cols[destinationIndex].equals(airportID);
							forwards = true;
							found = true;
						} else {
							// originally it seemed that sometimes the coords are in forwards order, other times in reverse order.
							// now (see further above) this is likely not true, but the org/dest codes are wrong, so just look at the coords themselves in reverse order
							// before anything else, we should take a look at the first and last coordinates to see if either is close to the airport in question
							double firstLat = Double.parseDouble(s[0]);
							double firstLon = Double.parseDouble(s[1]);
							double lastLat = Double.parseDouble(s[s.length - pathIncrement]);
							double lastLon = Double.parseDouble(s[(s.length - pathIncrement) + 1]);
							if (isCoordsNearAirport(firstLat, firstLon, latAirport, lonAirport, airportRadius)) {
								// coords near start, so airport was destination
								inbound = true;
								found = true;
							} else if (isCoordsNearAirport(lastLat, lastLon, latAirport, lonAirport, airportRadius)) {
								// coords near end, so airport was origin
								outbound = true;
								found = true;
							} else {
								// airport wasn't found at beginning or end. Maybe it's somewhere in the middle? Direction is a bit less certain, assume it's backwards (this seems to mostly be the case)
								// first, find where the movement at the airport is
								for (int i = 0; !found && i < s.length - (pathIncrement - 1); i+=pathIncrement) {
									double lat = Double.parseDouble(s[i]);
									double lon = Double.parseDouble(s[i+1]);
									double alt = Double.parseDouble(s[i+2]);
									if ((alt == 0) && isCoordsNearAirport(lat, lon, latAirport, lonAirport, airportRadius)) {
										forwards = false;
										found = true;
									}
								}
							}
						}

						// aircraft might have more than one set of ground movements at same airport (usually because ADS-B box wasn't reset at a remote destination)
						List<List<TimeCoordinate>> coordsForThisAircraft = new ArrayList<List<TimeCoordinate>>();
						List<TimeCoordinate> currentCoordsForThisAircraft = null;
						boolean previousCoordWasNotAtAirport = true; // when this shows that we've moved from a non-local coord to one at the airport, create a new track
						long previousTimestamp = startDate;
						
						// now create a list of the coords for taxiing
						// array is lat,lon,altitude,interval,...
						// work through forwards or backwards as appropriate
						if (found) {
							boolean done = false;

							for (int i = (forwards ? 0 : s.length - pathIncrement); (forwards ? (i < s.length) : (i >= 0)) && !done; i+=((forwards?1:-1)*pathIncrement)) {
								double altitude = Double.parseDouble(s[i+2]);
	
								// always read this, as all coord timestamps are intervals relative to previous, so to get one we need to get all before it
								int interval = includesIntervals ? (snapped?1:-1)*(int)(Double.parseDouble(s[i+3])) : 1; // parse as double and cast to int; sometimes we have .0 added to the figures, and -ve because FR24 has them as <0 already
								long timestamp = previousTimestamp + interval; // timestamp is the interval associated with this coord after the previous timestamp (or the timestap column if on the first coord)
								
								if (interval > breakTracksIfGapOverS) { // is interval over allowed threshold? if so, split
									currentCoordsForThisAircraft = new ArrayList<TimeCoordinate>();
									coordsForThisAircraft.add(currentCoordsForThisAircraft);
									previousCoordWasNotAtAirport = false; // also set this so we don't split again in a moment
								}
								
								if (altitude == 0) {
									double lat = Double.parseDouble(s[i]);
									double lon = Double.parseDouble(s[i+1]);
									
									if (isCoordsNearAirport(lat, lon, latAirport, lonAirport, airportRadius)) {
										// have we just newly found a block of coords at the airport?
										if (previousCoordWasNotAtAirport) {
											currentCoordsForThisAircraft = new ArrayList<TimeCoordinate>();
											coordsForThisAircraft.add(currentCoordsForThisAircraft);
											previousCoordWasNotAtAirport = false;
										}
										
										TimeCoordinate tc = new TimeCoordinate(new LatLng(lat, lon), timestamp, interval);
										currentCoordsForThisAircraft.add(tc);
									} else {
										previousCoordWasNotAtAirport = true;
									}
								} else {
									previousCoordWasNotAtAirport = true;
								}
								
								previousTimestamp = timestamp;
							}
						} // end of forwards/backwards block
						
						if (coordsForThisAircraft.size() > 0) {
							boolean insert = true;
							if (ids.contains(id)) { // first, see if we've already got data for this aircraft from another file - then keep whichever is the larger data set (this code supports reading multiple FR24 datasets, possibly overlapping)
								int i = ids.indexOf(id);
								if (aircrafts.get(i).coords.size() < coordsForThisAircraft.size()) {
									aircrafts.remove(i);
									ids.remove(i);
								} else {
									insert = false;
								}
							}
							
							if (insert) {
								Aircraft.Direction direction;
								if (inbound) {
									direction = Aircraft.Direction.INBOUND;
								} else if (outbound) {
									direction = Aircraft.Direction.OUTBOUND;
								} else {
									direction = Aircraft.Direction.STOPOFF;
								}
								
								// does the track have multiple visits to the airport in it? 
								if (SnapTracks.GLOBAL_DEBUG_LOAD_FILTERING) {
									System.out.println("Multiple ("+coordsForThisAircraft.size()+") tracks found for " + id);
								}
								countSplitTracks+= coordsForThisAircraft.size() - 1; // -1 as we're only counting additional tracks
								
								// run through the coords lists and drop any empty or nearly empty ones
								for (int i = coordsForThisAircraft.size() - 1; i >= 0; i--) {
									int size = coordsForThisAircraft.get(i).size();
									if (size < min) {
										coordsForThisAircraft.remove(i);
										
										if (SnapTracks.GLOBAL_DEBUG_LOAD_FILTERING) {
											System.out.println("Processed track has too few points (" + id + "):" + size);
										}
										countEmptyProcessedTracks++;
									}
								}

								if (coordsForThisAircraft.size() == 1) { // if only 1 track at airport then just add aircraft to list for return (if no tracks, don't bother) 
									Aircraft ac = new Aircraft(id, origin, destination, coordsForThisAircraft.isEmpty()?new ArrayList<TimeCoordinate>():coordsForThisAircraft.get(0), direction);
									aircrafts.add(ac);
									ids.add(id);
									countSingleValid++;
								} else if (coordsForThisAircraft.size() > 1) { // >1 track at airport. Create multiple AC objects, one for each track. This should reduce need for track splitting after snapping, and avoid nasty jumps in the coords
									int i = 0;
									for (List<TimeCoordinate> curCoords : coordsForThisAircraft) {
										String subID = id + "-" + (i++); 
										Aircraft ac = new Aircraft(subID, origin, destination, curCoords, direction);
										aircrafts.add(ac);
										ids.add(subID);
									}
									
									if (SnapTracks.GLOBAL_DEBUG_LOAD_FILTERING) {
										System.out.println("Multiple ("+coordsForThisAircraft.size()+") tracks found for " + id);
									}
									countMultipleValidTotal += coordsForThisAircraft.size();

									countMultipleValid++;
								} // ignore ACs with zero tracks 
							} else {
								countDroppedBecauseInMultipleFiles++;
							}
						} else {
							if (SnapTracks.GLOBAL_DEBUG_LOAD_FILTERING) {
								System.out.println("No track added:" + id);
							}
							
							countNoTracks++;
						}
					} else { // check for enough columns to include path
						if (SnapTracks.GLOBAL_DEBUG_LOAD_FILTERING) {
							System.out.println("Empty raw:" + id);
						}
						
						countEmptyRawTracks++;
					}
					
					lastSize = aircrafts.size();
				} // loop over file content
	
				in.close();
			} catch(IOException e) {
				System.err.println("Problem reading " + inFile + ":");
				e.printStackTrace();
				System.exit(1);
			}
		} // end of loop over files

		System.out.println("Loaded " + aircrafts.size() + " aircraft in total after validity checks and splitting.");
		System.out.println("Raw data contained " + countRawTracks + " across " + inFiles.length + " files");
		System.out.println("Tracks dropped because they were present in previous files: " + countDroppedBecauseInMultipleFiles);
		System.out.println("Tracks dropped because the raw data had no points near the airport, on the ground: " + countNoTracks);
		System.out.println("Tracks dropped because the raw data had too few (<" + min + ") points near airport, on the ground: " + countEmptyProcessedTracks);
		System.out.println("Tracks dropped because the raw data had no coordinates in the track: " + countEmptyRawTracks);
		System.out.println("Tracks added because the raw data had multiple visits to the airport: " + countSplitTracks);
		System.out.println("Aircraft with a single valid track: " + countSingleValid);
		System.out.println("Aircraft with multiple valid tracks: "+ countMultipleValid + ", (" + countMultipleValidTotal + ") tracks in total");

		return aircrafts;
	}
	
	public static class Aircraft implements Comparable<Aircraft> {
		private enum Direction {INBOUND,OUTBOUND,STOPOFF,UNKNOWN}
		private Direction direction;
		private List<TimeCoordinate> coords;
		private String id;
		private String origin;
		private String destination;
		
		public Aircraft(String id, String origin, String destination, List<TimeCoordinate> coords, Direction direction) {
			this.id = id;
			this.origin = origin;
			this.destination = destination;
			this.coords = coords;
			this.direction = direction;
		}
		
		public String getId() {
			return id;
		}
		
		public String getOrigin() {
			return origin;
		}
		
		public String getDestination() {
			return destination;
		}
		
		public Direction getDirection() {
			return direction;
		}
		
		public List<TimeCoordinate> getCoords() {
			return coords;
		}
		
		public String getLabel() {
			return id + " [" + origin + ">" + destination + "]";
		}
		
		@Override
		public String toString() {
			// collapse coords into single arrays
			double[] ccoords = new double[coords.size() * 2];
			for (int i = 0; i < coords.size(); i++) {
				ccoords[i * 2] = coords.get(i).getCoord().getLat();
				ccoords[(i * 2) + 1] = coords.get(i).getCoord().getLng();
			}
			
			ArrayTools.roundPlaces = ArrayTools.NOROUNDING;
			return getLabel() + SEPARATOR + direction + SEPARATOR + ArrayTools.toString(ccoords, SEPARATOR_COORDS);
		}

		@Override
		public int compareTo(Aircraft o) {
			return this.toString().compareTo(o.toString());
		}
		
		@Override
		public boolean equals(Object that) {
			return this.toString().equals(((Aircraft)that).toString());
		}
	}
	
	public static class TimeCoordinate {
		private LatLng coord;
		private long timestamp;
		private long interval;
		
		public TimeCoordinate(LatLng coord, long timestamp, long interval) {
			this.coord = coord;
			this.timestamp = timestamp;
			this.interval = interval;
		}
		
		public LatLng getCoord() {
			return coord;
		}
		
		public long getTimestamp() {
			return timestamp;
		}
		
		public long getInterval() {
			return interval;
		}
		
		public TimeCoordinate copyOf() {
			return new TimeCoordinate(new LatLng(this.coord.getLat(), this.coord.getLng()), this.timestamp, this.interval);
		}
		
		@Override
		public String toString() {
			return "TC[" + this.coord + "," + this.timestamp + "," + this.interval + "]";
		}
	}
}
