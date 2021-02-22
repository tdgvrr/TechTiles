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

import org.apache.pdfbox.exceptions.COSVisitorException;
import org.apache.pdfbox.pdmodel.common.COSArrayList;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckbox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioCollection;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextbox;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.DecryptionMaterial;
import org.apache.pdfbox.pdmodel.encryption.PublicKeyDecryptionMaterial;
import org.apache.pdfbox.pdmodel.encryption.StandardDecryptionMaterial;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.fdf.FDFDocument;
import org.apache.pdfbox.pdmodel.edit.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.xobject.PDJpeg;
import org.apache.pdfbox.pdmodel.graphics.xobject.PDXObjectImage;
import org.apache.pdfbox.util.ImageIOUtil;

// POPULATER: Utility to manipulate PDF documents using the PDFbox APIs.
//
// Command line syntax is: 
//
// java -jar Populater.jar <pdf-template> <pdf-output> <signature>   
//
// Where:
//
//    pdf-template: Input PDF - all formatting, content, etc comes from here.
//    pdf-output:   Output PDF - if changes are made, this is what gets created.
//    signature:    This is a file containing a graphical signature in JPEG format.
//
// The program reads a series of commands via STDIN that control processing, either 
// interactively or via something like echo "command command ..." | java -far Populater.jar ...
// These commands are defined: 
//
// LIST     Shows the structure of a PDF
// IMAGE    Saves the pages of the PDF as PNG images
// GET      Shows the value of a named or relative field in an existing PDF 
// SET      Sets fields in the PDF by field name or relative number
// SIGN     Stores the signature JPEG in a particular PDF field
// FONT     Sets the font appearance for subsequent text field updates
// QUIT     Saves any changes and exits

public class Populater 
{
 private static PDDocument _pdfDocument;
 private static int counter = 0;
 private static int depth = 0; 
 private static String font = "";

 public static void main(String[] args) 
 {                     
    if (args.length < 2)
    {
        System.out.println("ERROR: Missing arguments");
        System.exit(4);
    }

    String command = "";
    Boolean EOF        = false;
    Boolean did_change = false; 
    String originalPdf = args[0];
    String targetPdf   = args[1];
    String sigfile     = ""; 
    if (args.length > 2)
        sigfile = args[2];

    System.out.println("Input  PDF: " + args[0]);
    System.out.println("Output PDF: " + args[1]);
    
    if (args.length > 2)
        sigfile = args[2];
    
    System.out.println(" Signature: " + sigfile);

    try
    {  
        _pdfDocument = PDDocument.load(originalPdf);

        if (_pdfDocument.isEncrypted())
        {
            System.out.println("Exception: Document can't be decrypted...");
            StandardDecryptionMaterial dm = new StandardDecryptionMaterial("");
            _pdfDocument.openProtection(dm);
        }

        System.out.println("Document is " + _pdfDocument.getNumberOfPages() + " pages");
        PDDocumentCatalog docCatalog = _pdfDocument.getDocumentCatalog();
        PDAcroForm acroForm = docCatalog.getAcroForm();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                           
        for (int line = 1; !EOF ; line++)
        {
          System.out.println("Enter Command: [list, set, sign, get, font, quit]");
          command = in.readLine();
          if (command.startsWith("#"))    // Comment
              continue;

          command = command.trim();       // Blank lines
          if (command.length() == 0)
             continue;
          
          System.out.println("#" + line + ": " + command);
          if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit"))
             break;

          List<String> parts = new ArrayList<String>();
          Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(command);
          while (m.find())
             parts.add(m.group(1).replace("\"", ""));
        
         /* 
          System.out.println("Command parsed to " + parts.size() + " terms");
          for (int i = 0; i < parts.size(); i++)
             System.out.println("Parts " + i + " is " + parts.get(i));
          */

          switch(parts.get(0).toLowerCase())
          {
          case "oldlist":
              counter = 0;
              printStruct();
              printFields();
              break;
          
          case "list":
              ListFieldsByID();
              break;

          case "image":
              List<PDPage> pdPages = docCatalog.getAllPages();
              int page = 0;
              for (PDPage pdPage : pdPages)
              { 
                 ++page;
                 BufferedImage bim = pdPage.convertToImage(BufferedImage.TYPE_INT_RGB, 300);
                 ImageIOUtil.writeImage(bim, originalPdf + "-" + page + ".png", 300);
              }
              break;

          case "get":
              break;

          case "help":
              break;

          case "set":
              if (parts.size() < 3 || !parts.get(2).contains("="))
                System.out.println("ERROR: Missing operands for SET");
              else
              {
                String val = " "; 
                String field = parts.get(1);
                if (parts.size() > 3 && !parts.get(3).equalsIgnoreCase("null"))
                   val = parts.get(3);
                for (int i = 4; i < parts.size(); i++)
                   val = val + " " + parts.get(i);
                if (val.startsWith("##SKIP##"))
                   break;
                if (field.startsWith("#"))
                   setFieldById(Integer.parseInt(field.substring(1)), val);
                else
                   setField(field, val);
                did_change = true;
              }
              break;

          case "sign":
              if (sigfile.length() == 0)
                System.out.println("ERROR: Missing signature image file for SIGN");
              else
              {
                PDXObjectImage img = new PDJpeg(_pdfDocument, new FileInputStream(sigfile)); 
                System.out.println("Signature is " + img.getWidth() + " by " + img.getHeight());
                String field = parts.get(1);
                if (field.startsWith("#"))
                   signById(Integer.parseInt(field.substring(1)), img);
                else
                   System.out.println("sign(field, img);");
                did_change = true;

              }
              break;

          case "font": 
              if (parts.size() < 2) 
                  font = "";  // This is the default
              else
              {
                 for (int x = 1; x < parts.size(); x++)
                    font = new String(font + parts.get(x) + " ");
                 System.out.println("Default font: " + font);
              }
              break;

          default: 
              System.err.println("ERROR: Unknown operation - " + parts.get(0));
          } // switch...
       } // for...

       if (did_change)
       {
          System.out.println("Saving PDF to " + targetPdf);
          if (acroForm.getXFA() != null)
             acroForm.setXFA(null);
          System.out.println("Version was " + _pdfDocument.getDocument().getVersion());
          _pdfDocument.getDocument().setVersion(1.4f);
          _pdfDocument.save(targetPdf);
       }
       else
          System.out.println("No changes - PDF not saved");

       _pdfDocument.close();
    } // try...

    catch (Exception e)
    {
       System.out.println("Exception: " + e);
       e.printStackTrace();
       command = "exit";
       EOF = true;
    }
                                               
    System.out.println("Complete");
 }
                                      
public static void setField(String name, String value ) throws IOException 
{
   PDDocumentCatalog docCatalog = _pdfDocument.getDocumentCatalog();
   PDAcroForm acroForm = docCatalog.getAcroForm();
   PDField field = acroForm.getField( name );
   if( field != null ) 
   {
      List kids = field.getKids();
      if (kids != null) 
      {
         Iterator kidsIter = kids.iterator();
         while(kidsIter.hasNext()) 
         {
            Object pdfObj = kidsIter.next();
            if (pdfObj instanceof PDField) 
            {
               PDField kid = (PDField)pdfObj;
               System.err.println("WARNING: Field " + name + " has child field " + kid.getFullyQualifiedName());
            }
         }
      } // if kids != null 

      if (field.getClass().getName().contains("Checkbox"))
      {
          PDCheckbox cb = (PDCheckbox)field;
          if (value.contains("true") || value == "yes" || value == "1")
             cb.check();
          else
             cb.unCheck();
      }
      else
          if (field.getClass().getName().contains("Signature"))
             field.setValue(value);
          else
             field.setValue(value);
   }
   else 
   {
      System.err.println( "No field found with name:" + name );
   }
}


public static void ListFieldsByID() throws IOException 
{
   String name = "";
   PDDocumentCatalog docCatalog = _pdfDocument.getDocumentCatalog();
   PDAcroForm acroForm = docCatalog.getAcroForm();
   if (acroForm == null)
   {
      System.out.println("ERROR: No form fields found");
      return;
   }

   List<PDField> fields = acroForm.getFields();

   System.out.println("Found " + fields.size() + " fields");

   for (int id = 0; id < fields.size(); id++)
   {
      System.out.println("---Field " + id + " of " + fields.size() + "---");
      PDField field = fields.get(id);
      if (field == null)
      {
          System.out.println("WARNING: Field " + id + " not found");
          continue; 
      }
      String outputString = "Name:" + field.getFullyQualifiedName() + 
                            "\n    AltName:" + field.getAlternateFieldName() + 
                            "\n    Type:"    + field.getClass().getName() +  
                            "\n    Flags:"   + field.getFieldFlags() +
                            "\n    FType:"   + field.getFieldType() +
                            "\n    Parent:"  + field.getParent().getFullyQualifiedName(); 
      if (!field.getClass().getName().contains("Signature"))
         outputString += "\n    Value:"   + field.getValue();
      if (field.getWidget() != null)
      {
         outputString += "\n    Widget:" + field.getWidget().toString(); 
         outputString += "\n    Hidden:" + field.getWidget().isHidden(); 
         outputString += "\n    Visible:" + field.getWidget().isInvisible(); 
      }
      if (field.getKids() == null)
      {
         PDRectangle r = getFieldArea(field);
         outputString += "\n    Coordinates: " + r.getLowerLeftX() +"/" + r.getLowerLeftY() + 
                         " to " + r.getUpperRightX()  +"/" + r.getUpperRightY();
      }
      else
      {
         outputString += "\n    (Field has kids)";
      }

      System.out.println(outputString);
   }

   System.out.println("Field list complete"); 
}

public static void setFieldById(int id, String value ) throws IOException 
{
   String name = "";
   PDDocumentCatalog docCatalog = _pdfDocument.getDocumentCatalog();
   PDAcroForm acroForm = docCatalog.getAcroForm();
   if (acroForm == null)
   {
      System.out.println("No form fields found");
      return;
   }
   acroForm.setXFA(null); 
   List<PDField> fields = acroForm.getFields();

   if (id > fields.size())
   {
      System.out.println("ERROR: Requested field " + id + " but document only has " + fields.size() + " fields");
      return;
   }

   PDField field = fields.get(id);
   if (field == null)
   {
      System.out.println("Field " + id + " is not found");
      return;
   }

   System.out.println("Field " + id + " is " + field.getClass().getName() + " " + field.getFullyQualifiedName());
   
   if (field.getClass().getName().contains("Radio"))
   {
      PDRadioCollection rc = (PDRadioCollection)field;
      System.out.println("Radio BEFORE value: " + rc.getValue());
      rc.setValue(value);
      field.setReadonly(true); 
      System.out.println("Radio AFTER value: " + rc.getValue());
      return; 
   }
   
   if (field.getClass().getName().contains("Checkbox"))
   {
      PDCheckbox cb = (PDCheckbox)field;
      System.out.println("Checkbox BEFORE value: " + cb.getValue());
      if (value.contains("true") || value == "yes" || value == "1")
         cb.check();
         // cb.setValue(cb.getOnValue());
      else
         cb.unCheck();
         // cb.setValue(cb.getOffValue());
      System.out.println("Checkbox AFTER value: " + cb.getValue());
      field.setReadonly(true); 
      return; 
    }
    
    if (field instanceof PDVariableText && font != "")
    {
       COSDictionary dict = ((PDField)field).getDictionary();
       COSString defaultAppearance = (COSString) dict.getDictionaryObject(COSName.DA);
       System.out.println("Appearance: " + defaultAppearance.toString() + ", now " + font);
       dict.setString(COSName.DA, font);
       field = new PDTextbox(acroForm, dict); 
       ((PDField)field).setValue(value);
       ((PDField)field).setReadonly(true); 
    }
    else
    {
       field.setValue(value);
       field.setReadonly(true); 
    }
}

@SuppressWarnings("rawtypes")
public static PDRectangle getFieldArea(PDField field) 
{
   COSDictionary fieldDict = field.getDictionary();
   COSArray fieldAreaArray = (COSArray) fieldDict.getDictionaryObject(COSName.RECT);
   PDRectangle result = new PDRectangle(fieldAreaArray);
   return result;
}

@SuppressWarnings("rawtypes")
public static void printFields() throws IOException 
{
   depth = 0;
   PDDocumentCatalog docCatalog = _pdfDocument.getDocumentCatalog();
   PDAcroForm acroForm = docCatalog.getAcroForm();
   if (acroForm != null)
   {
      List fields = acroForm.getFields();
      Iterator fieldsIter = fields.iterator();

      while( fieldsIter.hasNext()) 
      {
         PDField field = (PDField)fieldsIter.next();
         processField(field, depth + ">", field.getPartialName());
      }
   }
   else
      System.out.println("No form fields found");
}

@SuppressWarnings("rawtypes")
public static void printStruct() throws IOException 
{
   depth = 0;
   PDDocumentCatalog docCatalog = _pdfDocument.getDocumentCatalog();
   PDAcroForm acroForm = docCatalog.getAcroForm();
   if (acroForm != null)
   {
      List fields = acroForm.getFields();
      Iterator fieldsIter = fields.iterator();
      System.out.println("List: " + new Integer(fields.size()).toString() + " top-level fields found on the form");

      while( fieldsIter.hasNext()) 
      {
         PDField field = (PDField)fieldsIter.next();
         processStruct(field, depth + ">", field.getPartialName());
      }
   }
   else
      System.out.println("No form fields found");
}

@SuppressWarnings("rawtypes")
private static void processField(PDField field, String sLevel, String sParent) throws IOException
{
   if (field.getClass().getName().contains("PDRadio"))
   {
      String outputString = "Name:" + field.getFullyQualifiedName() + 
                            "\n    AltName:" + field.getAlternateFieldName() + 
                            "\n    Type:" + field.getClass().getName() + 
                            "\n    Sequence:" + (counter++);
      System.out.println(outputString);
   }

   List kids = field.getKids();
   if (kids != null) 
   {
      Iterator kidsIter = kids.iterator();
      if (!sParent.equals(field.getPartialName())) 
      {
         sParent = sParent + "." + field.getPartialName();
      }

      //System.out.println(sLevel + sParent);

      while(kidsIter.hasNext()) 
      {
         Object pdfObj = kidsIter.next();
         if (pdfObj instanceof PDField) 
         {
            PDField kid = (PDField)pdfObj;
            processField(kid, ++depth + sLevel, sParent);
         }
      }
   }
   else 
   {
      String fieldType = field.getClass().getName();
      if (fieldType.contains("PDRadio"))
        fieldType = "RadioButtons";
      if (fieldType.contains("PDCheckbox"))
        fieldType = "Checkbox";
      if (fieldType.contains("PDTextbox"))
        fieldType = "Textbox";
      if (fieldType.contains("PDSignature"))
        fieldType = "Signature";

      PDRectangle r = getFieldArea(field);

      String outputString = "Name:" + field.getFullyQualifiedName() + 
                            "\n    AltName:" + field.getAlternateFieldName() + 
                            "\n    Type:" + fieldType + 
                            "\n    Parent:" + sParent +  
                            "\n    Sequence:" + (counter++) + 
                            "\n    Coordinates: " + r.getLowerLeftX() +"/" + r.getLowerLeftY() + 
                                      " to " + r.getUpperRightX()  +"/" + r.getUpperRightY();
  /*                         
      if (fieldType.contains("Textbox"))
      {
          Collection<COSBase> dict = field.getDictionary().getValues();

          Iterator<COSBase> iterator = dict.iterator();
          while (iterator.hasNext()) 
          {
             outputString += "\n    Dictionary: " + iterator.next().toString(); 
          }
      }
 */

      System.out.println(outputString);
   }
   depth--;
}

@SuppressWarnings("rawtypes")
private static void processStruct(PDField field, String sLevel, String sParent) throws IOException
{

   List kids = field.getKids();
   if (kids != null) 
   {
      Iterator kidsIter = kids.iterator();
      if (!sParent.equals(field.getPartialName())) 
      {
         sParent = sParent + "." + field.getPartialName();
      }

      System.out.println(sLevel + sParent);

      while(kidsIter.hasNext()) 
      {
         Object pdfObj = kidsIter.next();
         if (pdfObj instanceof PDField) 
         {
            PDField kid = (PDField)pdfObj;
            processStruct(kid, ++depth + " " + sLevel, sParent);
         }
      }
   }
      
   depth--;
}

public static void signById(int id, PDXObjectImage img ) throws IOException 
{
   String name = "";
   PDDocumentCatalog docCatalog = _pdfDocument.getDocumentCatalog();
   PDAcroForm acroForm = docCatalog.getAcroForm();
   if (acroForm == null)
   {
      System.out.println("No form fields found");
      return;
   }

   List<PDField> fields = acroForm.getFields();

   if (id > fields.size())
   {
      System.out.println("ERROR: Requested field " + id + " but document only has " + fields.size() + " fields");
      return;
   }

   PDField field = fields.get(id);
   int pageNumber = docCatalog.getAllPages().indexOf(field.getWidget().getPage());
   PDRectangle r = getFieldArea(field);
   System.out.println("Name:" + field.getFullyQualifiedName() + 
                      "\n    Page: " + pageNumber + 
                      "\n    Coordinates: " + r.getLowerLeftX() + "/" + r.getLowerLeftY() + 
                      " to " + r.getUpperRightX()  + "/" + r.getUpperRightY());

   if( field == null ) 
   {
      System.err.println( "No field found with ID " + id );
      return;
   }

   Float w = r.getUpperRightX() - r.getLowerLeftX();
   Float h = r.getUpperRightY() - r.getLowerLeftY();
  
   // ?? TEST 
   // img.setWidth(Math.round(w));
   // img.setHeight(Math.round(h));
   System.out.println("Image adjusted to " + Math.round(w) + " by " + Math.round(h));
  
   field.getWidget().setInvisible(true); 
   field.getWidget().setHidden(true); 
   List<PDPage> pages = docCatalog.getAllPages();
   PDPageContentStream stream = new PDPageContentStream( _pdfDocument, pages.get(pageNumber), true, true);
   stream.drawXObject(img, r.getLowerLeftX(), r.getLowerLeftY(), w, h);  
   stream.close();

   System.out.println("Signature written");

}

}
