#!/bin/bash
# Linux Script to build Doradus distribution

DIST_VERSION=2.4
DIST_FILE_NAME=Doradus-distribution-$DIST_VERSION.tar

#Build Doradus
cd ..
mvn clean install dependency:copy-dependencies -Dgpg.skip=true -Dmaven.javadoc.skip=true

cd doradus-distribution

#Installing cassandra 2.0.7  
if [ ! -d cassandra ]
then 
    echo "Installing and configuring cassandra for Doradus..."
    tar -xzvf dsc-cassandra-2.0.7-bin.tar.gz    
    mv dsc-cassandra-2.0.7 cassandra
    mkdir cassandra-data
    cd cassandra-data
    mkdir data
    mkdir saved_caches
    mkdir commitlog
    touch system.log
    cd ../cassandra/conf/
    sed -e 's,/var/lib/cassandra,./cassandra-data,' -i "" cassandra.yaml 
    sed -e 's,/var/log/cassandra,./cassandra-data,' -i "" log4j-server.properties
    cd ../..
fi    

#Clean up the doradus directory to place the new build binaries
if [ -d doradus ]
then
    rm -rf doradus
fi


#Create directories and copy all necessary files for doradus-dist-run script to run successfully

mkdir doradus
cd doradus
mkdir dependency
mkdir resources
cp ../../doradus-jetty/target/doradus-jetty*.jar .
cp ../../doradus-jetty/target/dependency/*.jar dependency/
cp ../../doradus-server/src/main/resources/* resources/
cp ../../doradus-client/target/doradus-client-*.jar dependency/

cd ..

#Create the final distribution file
tar -cvf $DIST_FILE_NAME --exclude="*._*" --exclude="doradus-dist-build.sh" --exclude="doradus-dist-build.bat" --exclude="$DIST_FILE_NAME" .

echo "Doradus Distribution created"
ls -la Doradus-distribution-*.tar