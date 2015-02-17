package gmtools.graph;

import gmtools.common.GroundMovementWriter;
import gmtools.tools.TaxiGen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jgrapht.graph.WeightedMultigraph;

import uk.me.jstott.jcoord.LatLng;

/**
 * This will take a taxiway graph, and cluster the edges in it so that we can quickly access the edges near to a certain point
 * The clusters are in a grid pattern, overlaid on the airport. When providing a point, you'll only get the edges in the grid squares
 * containing it (and neighbouring ones if they're close enough)
 */
public class EdgeClusters {
	private List<List<Set<TaxiEdge>>> grid;
	
	private int gridSize;
	
	private double gridCellSizeLat;
	
	private double gridCellSizeLon;

	private double latFromCellEdgeToGetNeighbourToo;
	private double lonFromCellEdgeToGetNeighbourToo;
	
	double minLat;
	double minLon;
	double maxLat;
	double maxLon;
	
	public EdgeClusters(TaxiGen taxiGen, int gridSize, double distanceMFromCellEdgeToGetNeighbourToo) {
		this.gridSize = gridSize;
		this.grid = new ArrayList<>(gridSize);
		for (int i = 0; i < gridSize; i++) {
			List<Set<TaxiEdge>> l = new ArrayList<>(gridSize);
			this.grid.add(l);
			for (int j = 0; j < gridSize; j++) {
				l.add(new HashSet<TaxiEdge>());
			}
		}
		
		WeightedMultigraph<TaxiNode, TaxiEdge> graph = taxiGen.getGraphWholeAirport();
		
		// work out lat and lng of airport boundary
		this.minLat = Double.POSITIVE_INFINITY;
		this.minLon = Double.POSITIVE_INFINITY;
		this.maxLat = Double.NEGATIVE_INFINITY;
		this.maxLon = Double.NEGATIVE_INFINITY;
		
		for (TaxiNode tn : graph.vertexSet()) {
			minLat = Math.min(minLat, tn.getLatCoordinate());
			minLon = Math.min(minLon, tn.getLonCoordinate());
			maxLat = Math.max(maxLat, tn.getLatCoordinate());
			maxLon = Math.max(maxLon, tn.getLonCoordinate());
		}
		
		// work out how many degrees lat/lng the padding distance is
		LatLng ll1 = new LatLng(minLat, minLon);
		LatLng ll2 = new LatLng(maxLat, minLon);
		LatLng ll3 = new LatLng(minLat, maxLon);
		
		double dLat = ll1.distance(ll2)*1000.0;
		double dLatFrac = 10.0 / dLat;
		this.latFromCellEdgeToGetNeighbourToo = dLatFrac * (maxLat - minLat);
		double dLon = ll1.distance(ll3)*1000.0;
		double dLonFrac = 10.0 / dLon;
		this.lonFromCellEdgeToGetNeighbourToo = dLonFrac * (maxLon - minLon);
		
		// tweak mins and maxs before processing edges, so we have a bit of padding all round
		this.minLat -= this.latFromCellEdgeToGetNeighbourToo;
		this.maxLat += this.latFromCellEdgeToGetNeighbourToo;
		this.minLon -= this.lonFromCellEdgeToGetNeighbourToo;
		this.maxLon += this.lonFromCellEdgeToGetNeighbourToo;
		
		this.gridCellSizeLat = (maxLat - minLat) / gridSize;
		this.gridCellSizeLon = (maxLon - minLon) / gridSize;
		
//	   	final Kml kml = KmlFactory.createKml();
//		final Document document = kml.createAndSetDocument().withName("test").withOpen(true);
//		final Style stylePoint = document.createAndAddStyle().withId("placemarkStyle");
//		stylePoint.createAndSetIconStyle().withIcon(new Icon().withHref("http://www.google.com/mapfiles/marker.png")).withColor("ffff7777").withScale(1);
//		final Style styleLine = document.createAndAddStyle().withId("linestyle");
//		styleLine.createAndSetLineStyle().withColor("5500ffff").withWidth(4.0d);
//
//		for (int i = 0; i <= gridSize; i++) {
//			LineString ls = document.createAndAddPlacemark().withName(Integer.toString(i)).withStyleUrl("#linestyle").withVisibility(true).createAndSetLineString(); //.withExtrude(true); //.withTessellate(true);
//			ls.addToCoordinates(minLon, minLat + (i * gridCellSizeLat), 0);
//			ls.addToCoordinates(maxLon, minLat + (i * gridCellSizeLat), 0);
//			LineString ls2 = document.createAndAddPlacemark().withName(Integer.toString(i)).withStyleUrl("#linestyle").withVisibility(true).createAndSetLineString(); //.withExtrude(true); //.withTessellate(true);
//			ls2.addToCoordinates(minLon + (i * gridCellSizeLon), minLat, 0);
//			ls2.addToCoordinates(minLon + (i * gridCellSizeLon), maxLat, 0);
//			
////				Placemark p2 = documentTrack.createAndAddPlacemark().withName(track.tcs.get(i).getLabel()).withStyleUrl((i == track.tcs.size()-1)?"#placemarkStyleLastPoint":"#placemarkStyleSubsequentPoint").withVisibility(false);
////				p2.createAndSetPoint().addToCoordinates(track.tcs.get(i).coords.getLng(), track.tcs.get(i).coords.getLat());
//		}
//		
//		try {
//			kml.marshal(new File("test.kml"));
//		} catch (IOException e) {
//			e.printStackTrace();
//		}

		
		
		processEdges(graph.edgeSet());
	}
	
	private void processEdges(Set<TaxiEdge> edges) {
		// for each edge, work out the cells it belongs too. These are the cells for the ends, and a rectangle
		// with those are the corners
		for (TaxiEdge te : edges) {
//			System.out.println("Processing " + te.getId());
			int[] cellTnFrom = getCellForPoint(new LatLng(te.getTnFrom().getLatCoordinate(), te.getTnFrom().getLonCoordinate()));
			int[] cellTnTo = getCellForPoint(new LatLng(te.getTnTo().getLatCoordinate(), te.getTnTo().getLonCoordinate()));

			int incx = (cellTnFrom[0] > cellTnTo[0]) ? -1 : 1;
			int incy = (cellTnFrom[1] > cellTnTo[1]) ? -1 : 1;
			
			for (int x = cellTnFrom[0]; (incx>0) ? x <= cellTnTo[0] : x >= cellTnTo[0]; x+=incx) {
				for (int y = cellTnFrom[1]; (incy>0) ? y <= cellTnTo[1] : y >= cellTnTo[1]; y+=incy) {
//					System.out.println("Adding " + te.getId() + " to " + x + "," + y);
					this.grid.get(x).get(y).add(te);
				}
			}
		}
	}
	
	// TODO also return whether next cell or prev cell should be included based on distance measure
	// elements 3 and 4 are 0 if no neighbour needed, or -1 +1 if they are
	private int[] getCellForPoint(LatLng point) {
		double lat = point.getLat();
		double lon = point.getLng();
		
		// subtract min
		lat -= this.minLat;
		lon -= this.minLon;
		
		// divide by square size
		double dx = lat / this.gridCellSizeLat;
		double dy = lon / this.gridCellSizeLon;
		
		// floor
		int x = (int)(Math.floor(dx));
		int y = (int)(Math.floor(dy));
		
		// distance from the min lat for this box
		double distanceMinLat = lat - (x * this.gridCellSizeLat);
		int addLatCell;
		if (distanceMinLat < this.latFromCellEdgeToGetNeighbourToo) {
			addLatCell = Math.max(0, x - 1); // check we don't go off the edge!
		} else if ((this.gridCellSizeLat - distanceMinLat) < this.latFromCellEdgeToGetNeighbourToo) {
			addLatCell = Math.min((this.gridSize-1), x + 1); // check we don't go off the edge!#
		} else {
			addLatCell = -1;
		}
		
		double distanceMinLon = lon - (y * this.gridCellSizeLon);
		int addLonCell;
		if (distanceMinLon < this.lonFromCellEdgeToGetNeighbourToo) {
			addLonCell = Math.max(0, y - 1); // check we don't go off the edge!
		} else if ((this.gridCellSizeLon - distanceMinLon) < this.lonFromCellEdgeToGetNeighbourToo) {
			addLonCell = Math.min((this.gridSize-1), y + 1); // check we don't go off the edge!#
		} else {
			addLonCell = -1;
		}
		
		// this is index
		return new int[] {x, y, addLatCell, addLonCell};
	}
	
	public Set<TaxiEdge> getEdgesNearPoint(LatLng point) {
		// return empty set if out of all bounds
		if ((point.getLat() < (this.minLat)) || (point.getLat() > this.maxLat) || (point.getLng() < this.minLon) || (point.getLng() > this.maxLon)) {
			return Collections.emptySet();
		}
		
		int[] cells = getCellForPoint(point);
		
		Set<TaxiEdge> rval = new HashSet<>(this.grid.get(cells[0]).get(cells[1]));
		if (cells[2] >= 0) {
			rval.addAll(this.grid.get(cells[2]).get(cells[1]));
		}
		if (cells[3] >= 0) {
			rval.addAll(this.grid.get(cells[0]).get(cells[3]));
		}
		if ((cells[2] >= 0)&&(cells[3] >= 0)) {
			rval.addAll(this.grid.get(cells[2]).get(cells[3]));
		}
		
		return rval;
	}
	
	public static void main(String[] args) {
		// load existing GM file
		GroundMovementWriter gmw = new GroundMovementWriter("C:\\sb\\AirportOperations\\CGN\\CGN_osm_GM.txt");
		
		// create an autotaxiways object from existing GM file
		TaxiGen at = new TaxiGen(gmw); 
		
		EdgeClusters ec = new EdgeClusters(at, 10, 10);
		
//		System.out.println("MinLat:" + ec.minLat);
//		System.out.println("MinLon:" + ec.minLon);
//		System.out.println("MaxLat:" + ec.maxLat);
//		System.out.println("MaxLon:" + ec.maxLon);
//		System.out.println(Arrays.toString(ec.getCellForPoint(new LatLng(50.8839787, 7.1159967))));
		
//		// how to work out degrees from distance (m)
//		LatLng ll1 = new LatLng(50, 7);
//		LatLng ll2 = new LatLng(50.1, 7);
//		
//		double d = ll1.distance(ll2)*1000.0;
//		double dfrac = 10.0 / d;
//		double latFrac = dfrac * (ll2.getLat() - ll1.getLat());
//		
//		System.out.println(latFrac);
//		System.out.println(ll1.distance(new LatLng(ll1.getLat()+latFrac,ll1.getLng()))*1000);
		
		System.out.println(at.getAllEdges().size());
		System.out.println(ec.getEdgesNearPoint(new LatLng( 50.879822,  7.125963)).size());
		System.out.println(ec.getEdgesNearPoint(new LatLng(   50.879864,      7.127285)).size());
		System.out.println(ec.getEdgesNearPoint(new LatLng(   50.880238,      7.125063)).size());
	}
}
