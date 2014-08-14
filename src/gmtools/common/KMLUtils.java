package gmtools.common;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import de.micromata.opengis.kml.v_2_2_0.Container;
import de.micromata.opengis.kml.v_2_2_0.Coordinate;
import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Feature;
import de.micromata.opengis.kml.v_2_2_0.Geometry;
import de.micromata.opengis.kml.v_2_2_0.GroundOverlay;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Point;

public class KMLUtils {
	/**
	 * write a ground overlay image and add to KML - this will allow a blank background to be set
	 * call this after adding everything so we can set the bounds properly
	 */
	public static void addGroundOverlayToKMLDocument(String filenameForKMLDocument, Document document) {
		try {
			// figure out bounds
			double[] bounds = getBoundsOfDocument(document, null);
			// add a little border to them (0.002 deg ought to do)
			double border = 0.002;
			bounds[0] -= border;
			bounds[1] += border;
			bounds[2] -= border;
			bounds[3] += border;
			
			// figure out parent dir to write file to
			File parent = new File(filenameForKMLDocument).getParentFile();
			if (parent == null) {
				parent = new File(".");
			}
			
			BufferedImage bi = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
			Graphics2D ig2 = bi.createGraphics();
			ig2.setPaint(Color.white);
			ig2.fillRect(0, 0, 100, 100);
			File blankFile = new File(parent, "blank.png");
			ImageIO.write(bi, "PNG", blankFile);
			
			GroundOverlay overlay = document.createAndAddGroundOverlay();
			overlay.createAndSetIcon().setHref(blankFile.getName());
			overlay.withName("Blank background");
			overlay.createAndSetLatLonBox().withSouth(bounds[0]).withNorth(bounds[1]).withWest(bounds[2]).withEast(bounds[3]);
		} catch (IOException e) {} // do nothing if we can't write a blank file!
	}
	
	/**
	 * call with 
	 * @return double[] with four elements: minLat(south),maxLat(north),minLon(west),maxLon(east)
	 */
	public static double[] getBoundsOfDocument(Document document, double[] boundsSoFar) {
		if (boundsSoFar == null) {
			boundsSoFar = new double[] {180,-180,180,-180};
		}
		
		for (Feature f : document.getFeature()){
			if (f instanceof Container) {
				boundsSoFar = getBoundsOfDocument((Document)f, boundsSoFar);
			}
			if (f instanceof Placemark) {
				Geometry g = ((Placemark)f).getGeometry();
				if (g instanceof LineString) {
					for(Coordinate c : ((LineString)g).getCoordinates()) {
						boundsSoFar[0] = Math.min(boundsSoFar[0], c.getLatitude());
						boundsSoFar[1] = Math.max(boundsSoFar[1], c.getLatitude());
						boundsSoFar[2] = Math.min(boundsSoFar[2], c.getLongitude());
						boundsSoFar[3] = Math.max(boundsSoFar[3], c.getLongitude());
					}
				} else if (g instanceof Point) {
					for(Coordinate c : ((Point)g).getCoordinates()) {
						boundsSoFar[0] = Math.min(boundsSoFar[0], c.getLatitude());
						boundsSoFar[1] = Math.max(boundsSoFar[1], c.getLatitude());
						boundsSoFar[2] = Math.min(boundsSoFar[2], c.getLongitude());
						boundsSoFar[3] = Math.max(boundsSoFar[3], c.getLongitude());
					}
				}
			}
		}
		
		return boundsSoFar;
	}
}
