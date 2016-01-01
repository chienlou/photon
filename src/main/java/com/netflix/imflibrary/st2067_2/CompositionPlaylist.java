/*
 *
 * Copyright 2015 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.netflix.imflibrary.st2067_2;

import com.netflix.imflibrary.exceptions.IMFException;
import com.netflix.imflibrary.utils.UUIDHelper;
import com.netflix.imflibrary.writerTools.utils.ValidationEventHandlerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smpte_ra.schemas.st2067_2_2013.*;
import org.xml.sax.SAXException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;

/**
 * This class represents a thin, immutable wrapper around the XML type 'CompositionPlaylistType' which is defined in Section 6.1,
 * st2067-3:2013. A CompositionPlaylist object can be constructed from an XML file only if it satisfies all the constraints specified
 * in st2067-3:2013 and st2067-2:2013
 */
@Immutable
public final class CompositionPlaylist
{
    private static final Logger logger = LoggerFactory.getLogger(CompositionPlaylist.class);

    private static final String imf_core_constraints_schema_path = "/org/smpte_ra/schemas/st2067_2_2013/imf-core-constraints-20130620-pal.xsd";
    private static final String imf_cpl_schema_path = "/org/smpte_ra/schemas/st2067_3_2013/imf-cpl.xsd";
    private static final String dcmlTypes_schema_path = "/org/smpte_ra/schemas/st0433_2008/dcmlTypes/dcmlTypes.xsd";
    private static final String xmldig_core_schema_path = "/org/w3/_2000_09/xmldsig/xmldsig-core-schema.xsd";

    private final CompositionPlaylistType compositionPlaylistType;
    private final UUID uuid;
    private final EditRate editRate;
    private final Map<UUID, VirtualTrack> virtualTrackMap;
    private final Map<UUID, List<TrackFileResourceType>> virtualTrackResourceList;

    /**
     * Constructor for a {@link com.netflix.imflibrary.st2067_2.CompositionPlaylist CompositionPlaylist} object from a XML file
     * @param compositionPlaylistXMLFile the input XML file that is conformed to schema and constraints specified in st2067-3:2013 and st2067-2:2013
     * @throws IOException - any I/O related error is exposed through an IOException
     * @throws SAXException - exposes any issues with instantiating a {@link javax.xml.validation.Schema Schema} object
     * @throws JAXBException - any issues in serializing the XML document using JAXB are exposed through a JAXBException
     * @throws URISyntaxException exposes any issues instantiating a {@link java.net.URI URI} object
     */
    public CompositionPlaylist(File compositionPlaylistXMLFile)  throws IOException, SAXException, JAXBException, URISyntaxException
    {

        CompositionPlaylist.validateCompositionPlaylistSchema(compositionPlaylistXMLFile);

        try(InputStream input = new FileInputStream(compositionPlaylistXMLFile);
            InputStream xmldig_core_is = CompositionPlaylist.class.getResourceAsStream(CompositionPlaylist.xmldig_core_schema_path);
            InputStream dcmlTypes_is = CompositionPlaylist.class.getResourceAsStream(CompositionPlaylist.dcmlTypes_schema_path);
            InputStream imf_cpl_is = CompositionPlaylist.class.getResourceAsStream(CompositionPlaylist.imf_cpl_schema_path);
            InputStream imf_core_constraints_is = CompositionPlaylist.class.getResourceAsStream(CompositionPlaylist.imf_core_constraints_schema_path);)
        {
            StreamSource[] streamSources = new StreamSource[4];
            streamSources[0] = new StreamSource(xmldig_core_is);
            streamSources[1] = new StreamSource(dcmlTypes_is);
            streamSources[2] = new StreamSource(imf_cpl_is);
            streamSources[3] = new StreamSource(imf_core_constraints_is);

            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = schemaFactory.newSchema(streamSources);

            ValidationEventHandlerImpl validationEventHandlerImpl = new ValidationEventHandlerImpl(true);
            JAXBContext jaxbContext = JAXBContext.newInstance("org.smpte_ra.schemas.st2067_2_2013");
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            unmarshaller.setEventHandler(validationEventHandlerImpl);
            unmarshaller.setSchema(schema);

            JAXBElement<CompositionPlaylistType> compositionPlaylistTypeJAXBElement = (JAXBElement) unmarshaller.unmarshal(input);
            if (validationEventHandlerImpl.hasErrors())
            {
                throw new IMFException(validationEventHandlerImpl.toString());
            }

            CompositionPlaylistType compositionPlaylistType = compositionPlaylistTypeJAXBElement.getValue();
            this.virtualTrackMap = checkVirtualTracks(compositionPlaylistType);

            this.compositionPlaylistType = compositionPlaylistType;
        }

        this.uuid = UUID.fromString(UUIDHelper.fromUUIDAsURNToUUID(this.compositionPlaylistType.getId()));

        this.editRate = new EditRate(this.compositionPlaylistType.getEditRate());

        this.virtualTrackResourceList = populateVirtualTrackResourceList();


    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=================== CompositionPlaylist : %s%n", this.uuid));
        sb.append(this.editRate.toString());
        return sb.toString();
    }

    /**
     * Getter for the composition edit rate as specified in the CompositionPlaylist XML file
     * @return the edit rate associated with the Composition
     */
    public EditRate getEditRate()
    {
        return this.editRate;
    }

    /**
     * Getter for the lists of type {@link org.smpte_ra.schemas.st2067_2_2013.TrackFileResourceType TrackFileResourceType} indexed by the VirtualTrackID.
     * The VirtualTrack concept is defined in Section 6.9.3 of st2067-3:2013.
     * @return Map&lt;UUID,List &lt;{@link org.smpte_ra.schemas.st2067_2_2013.TrackFileResourceType TrackFileResourceType}&gt;&gt;. The UUID key corresponds to VirtualTrackID
     */
    public Map<UUID, List<TrackFileResourceType>> getVirtualTrackResourceList()
    {
        return Collections.unmodifiableMap(this.virtualTrackResourceList);
    }

    /**
     * Getter for the virtual track map associated with this CompositionPlaylist
     * @return {@link java.util.Map Map}&lt;{@link java.util.UUID UUID},{@link com.netflix.imflibrary.st2067_2.CompositionPlaylist.VirtualTrack VirtualTrack}&gt;. The UUID key corresponds to VirtualTrackID
     */
    public Map<UUID, VirtualTrack> getVirtualTrackMap()
    {
        return Collections.unmodifiableMap(this.virtualTrackMap);
    }

    /**
     * Getter for the UUID corresponding to this CompositionPlaylist document
     * @return the uuid of this CompositionPlaylist object
     */
    public UUID getUUID()
    {
        return this.uuid;
    }

    Map<UUID, VirtualTrack> checkVirtualTracks(CompositionPlaylistType compositionPlaylistType)
    {
        Map<UUID, VirtualTrack> virtualTrackMap = new LinkedHashMap<>();
        //process first segment to form virtual track map
        SegmentType segment = compositionPlaylistType.getSegmentList().getSegment().get(0);
        SequenceType sequence;
        sequence = segment.getSequenceList().getMarkerSequence();
        if (sequence != null)
        {
            UUID uuid = UUID.fromString(UUIDHelper.fromUUIDAsURNToUUID(sequence.getTrackId()));
            if (virtualTrackMap.get(uuid) == null)
            {
                VirtualTrack virtualTrack = new VirtualTrack(uuid, SequenceTypeEnum.MarkerSequence);
                virtualTrackMap.put(uuid, virtualTrack);
            }
            else
            {//TODO: add error messaging
                throw new IMFException("");
            }
        }

        for (Object object : segment.getSequenceList().getAny())
        {
            JAXBElement jaxbElement = (JAXBElement)(object);
            String name = jaxbElement.getName().getLocalPart();
            sequence = (SequenceType)(jaxbElement).getValue();
            if (sequence != null)
            {
                UUID uuid = UUID.fromString(UUIDHelper.fromUUIDAsURNToUUID(sequence.getTrackId()));
                if (virtualTrackMap.get(uuid) == null)
                {
                    VirtualTrack virtualTrack = new VirtualTrack(uuid, SequenceTypeEnum.getSequenceTypeEnum(name));
                    virtualTrackMap.put(uuid, virtualTrack);
                }
                else
                {//TODO: add error messaging
                    throw new IMFException("");
                }
            }

        }

        checkSegments(compositionPlaylistType, virtualTrackMap);

        return virtualTrackMap;
    }

    private void checkSegments(CompositionPlaylistType compositionPlaylistType, Map<UUID, VirtualTrack> virtualTrackMap)
    {
        for (SegmentType segment : compositionPlaylistType.getSegmentList().getSegment())
        {
            Set<UUID> trackIDs = new HashSet<>();
            SequenceType sequence;
            sequence = segment.getSequenceList().getMarkerSequence();
            if (sequence != null)
            {
                UUID uuid = UUID.fromString(UUIDHelper.fromUUIDAsURNToUUID(sequence.getTrackId()));
                trackIDs.add(uuid);
                if (virtualTrackMap.get(uuid) == null)
                {//TODO: add error messaging
                    throw new IMFException("");
                }
            }

            for (Object object : segment.getSequenceList().getAny())
            {
                JAXBElement jaxbElement = (JAXBElement)(object);
                String name = jaxbElement.getName().getLocalPart();
                sequence = (SequenceType)(jaxbElement).getValue();
                if (sequence != null)
                {
                    UUID uuid = UUID.fromString(UUIDHelper.fromUUIDAsURNToUUID(sequence.getTrackId()));
                    trackIDs.add(uuid);
                    if (virtualTrackMap.get(uuid) == null)
                    {//TODO: add error messaging
                        throw new IMFException("");
                    }
                }
            }

            if (trackIDs.size() != virtualTrackMap.size())
            {//TODO: add error messaging
                throw new IMFException("");

            }


        }

    }

    private Map<UUID, List<TrackFileResourceType>> populateVirtualTrackResourceList()
    {
        Map<UUID, List<TrackFileResourceType>> virtualTrackResourceList = new LinkedHashMap<>();
        for (SegmentType segment : this.compositionPlaylistType.getSegmentList().getSegment())
        {

            SequenceType sequence;
            for (Object object : segment.getSequenceList().getAny())
            {
                JAXBElement jaxbElement = (JAXBElement)(object);
                sequence = (SequenceType)(jaxbElement).getValue();
                if (sequence != null)
                {
                    UUID uuid = UUID.fromString(UUIDHelper.fromUUIDAsURNToUUID(sequence.getTrackId()));
                    List<TrackFileResourceType> trackFileResources = new ArrayList<>();
                    for (BaseResourceType resource : sequence.getResourceList().getResource())
                    {
                        TrackFileResourceType trackFileResource = (TrackFileResourceType)resource;
                        trackFileResources.add(trackFileResource);
                    }
                    if (virtualTrackResourceList.get(uuid) == null)
                    {
                        virtualTrackResourceList.put(uuid, trackFileResources);
                    }
                    else
                    {
                        virtualTrackResourceList.get(uuid).addAll(trackFileResources);
                    }

                }
            }
        }

        //make virtualTrackResourceList immutable
        for(Map.Entry<UUID, List<TrackFileResourceType>> entry : virtualTrackResourceList.entrySet())
        {
            List<TrackFileResourceType> trackFileResources = entry.getValue();
            entry.setValue(Collections.unmodifiableList(trackFileResources));
        }

        return virtualTrackResourceList;
    }

    private static void validateCompositionPlaylistSchema(File xmlFile) throws IOException, URISyntaxException, SAXException
    {

        try(InputStream input = new FileInputStream(xmlFile);
            InputStream xmldig_core_is = CompositionPlaylist.class.getResourceAsStream(CompositionPlaylist.xmldig_core_schema_path);
            InputStream dcmlTypes_is = CompositionPlaylist.class.getResourceAsStream(CompositionPlaylist.dcmlTypes_schema_path);
            InputStream imf_cpl_is = CompositionPlaylist.class.getResourceAsStream(CompositionPlaylist.imf_cpl_schema_path);
            InputStream imf_core_constraints_is = CompositionPlaylist.class.getResourceAsStream(CompositionPlaylist.imf_core_constraints_schema_path);)
        {
            StreamSource inputSource = new StreamSource(input);

            StreamSource[] streamSources = new StreamSource[4];
            streamSources[0] = new StreamSource(xmldig_core_is);
            streamSources[1] = new StreamSource(dcmlTypes_is);
            streamSources[2] = new StreamSource(imf_cpl_is);
            streamSources[3] = new StreamSource(imf_core_constraints_is);

            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = schemaFactory.newSchema(streamSources);

            Validator validator = schema.newValidator();
            validator.validate(inputSource);
        }

    }

    /**
     * This class is an immutable implementation of a rational number described as a ratio of two longs and used to hold
     * non-integer frame rate values
     */
    @Immutable
    public static final class EditRate
    {
        private final long numerator;
        private final long denominator;

        /**
         * Constructor for the rational frame rate number.
         * @param numbers the input list of numbers. The first number in the list is treated as the numerator and the second as
         *                the denominator. Construction succeeds only if the list has exactly two numbers
         */
        public EditRate(List<Long> numbers)
        {
            if (numbers.size() != 2)
            {
                throw new IMFException(String.format(
                        "Input list is expected to contain 2 numbers representing numerator and denominator respectively, found %d numbers in list %s",
                        numbers.size(), Arrays.toString(numbers.toArray())));
            }
            this.numerator = numbers.get(0);
            this.denominator = numbers.get(1);
        }

        /**
         * Getter for the frame rate numerator
         * @return a long value corresponding to the frame rate numerator
         */
        public long getNumerator()
        {
            return this.numerator;
        }

        /**
         * Getter for the frame rate denominator
         * @return a long value corresponding to the frame rate denominator
         */
        public long getDenominator()
        {
            return this.denominator;
        }

        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("=================== EditRate =====================\n");
            sb.append(String.format("numerator = %d, denominator = %d%n", this.numerator, this.denominator));
            return sb.toString();
        }
    }

    /**
     * This class enumerates various types of {@link org.smpte_ra.schemas.st2067_2_2013.SequenceType Sequence} that are valid in
     * CompositionPlaylist document that is compliant with st2067-2:2013. Such types are mostly defined in Section 6.3 of st2067-2:2013
     */
    public static enum SequenceTypeEnum
    {
        MarkerSequence("MarkerSequence"),
        MainImageSequence("MainImageSequence"),
        MainAudioSequence("MainAudioSequence"),
        SubtitlesSequence("SubtitlesSequence"),
        HearingImpairedCaptionsSequence("HearingImpairedCaptionsSequence"),
        VisuallyImpairedTextSequence("VisuallyImpairedTextSequence"),
        CommentarySequence("CommentarySequence"),
        KaraokeSequence("KaraokeSequence"),
        AncillaryDataSequence("AncillaryDataSequence");

        private final String name;

        private SequenceTypeEnum(String name)
        {
            this.name = name;
        }

        private static @Nullable SequenceTypeEnum getSequenceTypeEnum(String name)
        {
            switch (name)
            {
                case "MainImageSequence":
                    return MainImageSequence;
                case "MainAudioSequence":
                    return MainAudioSequence;
                case "SubtitlesSequence":
                    return SubtitlesSequence;
                case "HearingImpairedCaptionsSequence":
                    return HearingImpairedCaptionsSequence;
                case "VisuallyImpairedTextSequence":
                    return VisuallyImpairedTextSequence;
                case "CommentarySequence":
                    return CommentarySequence;
                case "KaraokeSequence":
                    return KaraokeSequence;
                case "AncillaryDataSequence":
                    return AncillaryDataSequence;
                default:
                    return null;
            }
        }

    }

    /**
     * The class is an immutable implementation of the virtual track concept defined in Section 6.9.3 of st2067-3:2013. A
     * virtual track is characterized by its UUID and the type of sequence it holds
     */
    @Immutable
    public static final class VirtualTrack
    {
        private final UUID trackID;
        private final SequenceTypeEnum sequenceTypeEnum;

        /**
         * Constructor for a VirtualTrack object
         * @param trackID the UUID associated with this VirtualTrack object
         * @param sequenceTypeEnum the type of the associated sequence
         */
        public VirtualTrack(UUID trackID, SequenceTypeEnum sequenceTypeEnum)
        {
            this.trackID = trackID;
            this.sequenceTypeEnum = sequenceTypeEnum;
        }

        /**
         * Getter for the sequence type associated with this VirtualTrack object
         * @return the sequence type associated with this VirtualTrack object as an enum
         */
        public SequenceTypeEnum getSequenceTypeEnum()
        {
            return this.sequenceTypeEnum;
        }
    }

    public static void main(String args[]) throws Exception
    {
        File inputFile = new File(args[0]);

        CompositionPlaylist compositionPlaylist = new CompositionPlaylist(inputFile);
        logger.warn(compositionPlaylist.toString());
    }

}
