#!/bin/bash

#
# Simple script to build the "Populater"
#

SOURCE=Populater.java
SOURCE2=Pdf2Png.java
BASE=`basename -s .java $SOURCE`
BASE2=`basename -s .java $SOURCE2`
EXEC=$BASE.jar
EXEC2=$BASE2.jar
PDFBOX=`ls lib/pdfbox*.jar`
PDFBOX2=`ls lib2/pdfbox*.jar`
JAR=/usr/lib/jvm/jdk1.7.0_51/bin/jar
NOW=`/bin/date '+%d-%h-%Y'` 

if [ ! -f "$EXEC" ]; then
   cp $PDFBOX $EXEC
fi

javac -cp $PDFBOX $SOURCE
$JAR -ufe $EXEC $BASE $BASE.class

if [ ! -f "$EXEC2" ]; then
   cp $PDFBOX2 $EXEC2
fi

javac -cp $PDFBOX2 $SOURCE2
$JAR -ufe $EXEC2 $BASE2 $BASE2.class

echo "If the build worked, you can run the executable like this:"
echo "java -jar $EXEC"
