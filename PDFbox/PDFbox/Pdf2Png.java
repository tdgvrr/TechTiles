import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.lang.*;
import java.util.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.*;
import java.security.KeyStore;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.awt.image.BufferedImage;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.apache.pdfbox.rendering.ImageType;


// PDF2PNG: Utility to convert PDF documents to PNG images using the PDFbox APIs.
//
// Command line syntax is: 
//
// java -jar Pdf2Png.jar <pdf-input>    
//
// Where:
//
//    pdf-input:    Input PDF with forms, etc.
//
// The program simply "prints" a PDF by generating a PNG for each page of the input. 

public class Pdf2Png
{
 private static PDDocument _pdfDocument;

 public static void main(String[] args) 
 {                     
    if (args.length < 1)
    {
        System.out.println("ERROR: Missing arguments");
        System.exit(4);
    }
    String originalPdf = args[0];
    System.out.println("Input  PDF: " + args[0]);
    
    try
    {  
        PDDocument document = PDDocument.load(new File(originalPdf));
        System.out.println("Document is " + document.getNumberOfPages() + " pages");
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        for (int page = 0; page < document.getNumberOfPages(); ++page)
        { 
            BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 300, ImageType.RGB);
            ImageIOUtil.writeImage(bim, originalPdf + "-" + (page+1) + ".png", 300);
        }
        document.close();
    } // try...

    catch (Exception e)
    {
       System.out.println("Exception: " + e);
       e.printStackTrace();
    }
                                               
    System.out.println("Complete");
 }
}
