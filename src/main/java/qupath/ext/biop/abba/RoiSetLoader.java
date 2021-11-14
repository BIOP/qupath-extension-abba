package qupath.ext.biop.abba;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RoiSetLoader {
    final static Logger logger = LoggerFactory.getLogger( RoiSetLoader.class);

    // Taken directly from the RoiManager, so as to be able to run it concurrently
    // since the RoiManager only allows for one instance of itself to exist...
    public static ArrayList<Roi> openRoiSet( File path ) {
        ZipInputStream in = null;
        ByteArrayOutputStream out = null;
        int nRois = 0;
        ArrayList<Roi> rois = new ArrayList<>();
        try {
            in = new ZipInputStream(new FileInputStream(path));
            byte[] buf = new byte[1024];
            int len;
            ZipEntry entry = in.getNextEntry();
            while (entry != null) {
                String name = entry.getName();
                if (name.endsWith(".roi")) {
                    out = new ByteArrayOutputStream();
                    while ((len = in.read(buf)) > 0)
                        out.write(buf, 0, len);
                    out.close();
                    byte[] bytes = out.toByteArray();
                    RoiDecoder rd = new RoiDecoder(bytes, name);
                    Roi roi = rd.getRoi();
                    if (roi != null) {
                        name = name.substring(0, name.length() - 4);
                        rois.add(roi);
                        nRois++;
                    }
                }
                entry = in.getNextEntry();
            }
            in.close();
        } catch ( IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null)
                try {
                    in.close();
                } catch (IOException e) {
                }
            if (out != null)
                try {
                    out.close();
                } catch (IOException e) {
                }
        }
        if (nRois == 0) {
            logger.error("This ZIP archive does not contain '.roi' files: {}", path);
        }
        return rois;
    }
}
