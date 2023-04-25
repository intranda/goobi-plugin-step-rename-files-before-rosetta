package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.FileSet;
import ugh.dl.Fileformat;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class RenameFilesBeforeRosettaStepPlugin implements IStepPluginVersion2 {
    
    @Getter
    private String title = "intranda_step_rename_files_before_rosetta";
    @Getter
    private Step step;
    @Getter
    private String value;
    @Getter 
    private boolean allowTaskFinishButtons;
    private String returnPath;

    private Process process;

    private StorageProviderInterface storageProvider = StorageProvider.getInstance();

    private String newFileNamePrefix;

    private String derivateFolder;
    private String altoFolder;
    private String txtFolder;
    private String pdfFolder;
    private String xmlFolder;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
                
        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        value = myconfig.getString("value", "default value"); 
        allowTaskFinishButtons = myconfig.getBoolean("allowTaskFinishButtons", false);
        log.info("rename_files_before_rosetta step plugin initialized");
        log.debug("value = " + value);
        
        process = this.step.getProzess();
        initializeNewFileNamePrefix();
        try {
            derivateFolder = process.getImagesTifDirectory(false);
            altoFolder = process.getOcrAltoDirectory();
            txtFolder = process.getOcrTxtDirectory();
            pdfFolder = process.getOcrPdfDirectory();
            xmlFolder = process.getOcrXmlDirectory();
        } catch (IOException | SwapException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void initializeNewFileNamePrefix() {
        String processTitle = process.getTitel();
        newFileNamePrefix = processTitle.substring(processTitle.indexOf("_") + 1);
    }

    @Override
    public PluginReturnValue run() {
        boolean successful = true;
        // your logic goes here

        log.info("rename_files_before_rosetta step plugin executed");

        // 1. create a Map from old names to new names
        Map<String, String> namesMap = createNamesMap();

        // 2. rename files in each folder with help of this Map
        renameFilesInFolder(derivateFolder, namesMap);

        // 3. update the Mets file

        if (!successful) {
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }

    private Map<String, String> createNamesMap() {
        Map<String, String> namesMap = new HashMap<>();
        List<Path> files = storageProvider.listFiles(derivateFolder);
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String oldName = fileName.substring(0, fileName.lastIndexOf("."));
            // get new name based on this old name
            String newName = newFileNamePrefix + "_" + oldName;
            namesMap.put(oldName, newName);
        }

        return namesMap;
    }

    private boolean renameFilesInFolder(String folder, Map<String, String> namesMap) {
        List<Path> files = storageProvider.listFiles(folder);

        for (Path file : files) {
            // get new filename 
            String fileName = file.getFileName().toString();
            String newFileName = getNewFileName(fileName, namesMap);
            // get new file path
            Path targetPath = file.getParent().resolve(newFileName);
            log.debug("targetPath = " + targetPath);
        }

        return true;
    }

    private boolean updateMetsFile(Map<String, String> namesMap) {
        try {
            Fileformat fileformat = process.readMetadataFile();
            DigitalDocument dd = fileformat.getDigitalDocument();
            FileSet fileSet = dd.getFileSet();
            List<ContentFile> filesList = fileSet.getAllFiles();
            for (ContentFile file : filesList) {
                String oldLocation = file.getLocation();
                int fileNameStartIndex = oldLocation.lastIndexOf("/") + 1;
                String locationPrefix = oldLocation.substring(0, fileNameStartIndex);

                String oldFileName = oldLocation.substring(fileNameStartIndex);
                String newFileName = getNewFileName(oldFileName, namesMap);
                String newLocation = locationPrefix.concat(newFileName);
                file.setLocation(newLocation);
            }

            process.writeMetadataFile(fileformat);

            return true;

        } catch (ReadException | IOException | SwapException | PreferencesException | WriteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return false;
        }
    }

    private String getNewFileName(String oldFileName, Map<String, String> namesMap) {
        int suffixIndex = oldFileName.lastIndexOf(".");
        String suffix = oldFileName.substring(suffixIndex);
        String oldName = oldFileName.substring(0, suffixIndex);
        return namesMap.get(oldName).concat(suffix);
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.FULL;
        // return PluginGuiType.PART;
        // return PluginGuiType.PART_AND_FULL;
        // return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }
    
    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }
    
    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

}
