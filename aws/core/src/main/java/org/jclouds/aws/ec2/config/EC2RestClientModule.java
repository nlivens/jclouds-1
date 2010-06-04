/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.aws.ec2.config;

import java.net.URI;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.aws.domain.Region;
import org.jclouds.aws.ec2.EC2;
import org.jclouds.aws.ec2.ELB;
import org.jclouds.aws.ec2.domain.AvailabilityZoneInfo;
import org.jclouds.aws.ec2.domain.RunningInstance;
import org.jclouds.aws.ec2.predicates.InstanceStateRunning;
import org.jclouds.aws.ec2.predicates.InstanceStateTerminated;
import org.jclouds.aws.ec2.reference.EC2Constants;
import org.jclouds.aws.ec2.services.AMIAsyncClient;
import org.jclouds.aws.ec2.services.AMIClient;
import org.jclouds.aws.ec2.services.AvailabilityZoneAndRegionAsyncClient;
import org.jclouds.aws.ec2.services.AvailabilityZoneAndRegionClient;
import org.jclouds.aws.ec2.services.ElasticBlockStoreAsyncClient;
import org.jclouds.aws.ec2.services.ElasticBlockStoreClient;
import org.jclouds.aws.ec2.services.ElasticIPAddressAsyncClient;
import org.jclouds.aws.ec2.services.ElasticIPAddressClient;
import org.jclouds.aws.ec2.services.ElasticLoadBalancerAsyncClient;
import org.jclouds.aws.ec2.services.ElasticLoadBalancerClient;
import org.jclouds.aws.ec2.services.InstanceAsyncClient;
import org.jclouds.aws.ec2.services.InstanceClient;
import org.jclouds.aws.ec2.services.KeyPairAsyncClient;
import org.jclouds.aws.ec2.services.KeyPairClient;
import org.jclouds.aws.ec2.services.MonitoringAsyncClient;
import org.jclouds.aws.ec2.services.MonitoringClient;
import org.jclouds.aws.ec2.services.SecurityGroupAsyncClient;
import org.jclouds.aws.ec2.services.SecurityGroupClient;
import org.jclouds.aws.filters.FormSigner;
import org.jclouds.aws.handlers.AWSClientErrorRetryHandler;
import org.jclouds.aws.handlers.AWSRedirectionRetryHandler;
import org.jclouds.aws.handlers.ParseAWSErrorFromXmlContent;
import org.jclouds.concurrent.internal.SyncProxy;
import org.jclouds.date.DateService;
import org.jclouds.date.TimeStamp;
import org.jclouds.http.HttpErrorHandler;
import org.jclouds.http.HttpRetryHandler;
import org.jclouds.http.RequiresHttp;
import org.jclouds.http.annotation.ClientError;
import org.jclouds.http.annotation.Redirection;
import org.jclouds.http.annotation.ServerError;
import org.jclouds.net.IPSocket;
import org.jclouds.predicates.RetryablePredicate;
import org.jclouds.predicates.SocketOpen;
import org.jclouds.rest.ConfiguresRestClient;
import org.jclouds.rest.RequestSigner;
import org.jclouds.rest.RestClientFactory;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;

/**
 * Configures the EC2 connection.
 * 
 * @author Adrian Cole
 */
@RequiresHttp
@ConfiguresRestClient
public class EC2RestClientModule extends AbstractModule {

   @Provides
   @Singleton
   @Named("RUNNING")
   protected Predicate<RunningInstance> instanceStateRunning(InstanceStateRunning stateRunning) {
      return new RetryablePredicate<RunningInstance>(stateRunning, 600, 3, TimeUnit.SECONDS);
   }

   @Provides
   @Singleton
   @Named("TERMINATED")
   protected Predicate<RunningInstance> instanceStateTerminated(
            InstanceStateTerminated stateTerminated) {
      return new RetryablePredicate<RunningInstance>(stateTerminated, 20000, 500,
               TimeUnit.MILLISECONDS);
   }

   @Provides
   @Singleton
   protected Predicate<IPSocket> socketTester(SocketOpen open) {
      return new RetryablePredicate<IPSocket>(open, 130, 1, TimeUnit.SECONDS);
   }

   @Override
   protected void configure() {
      bindErrorHandlers();
      bindRetryHandlers();
   }

   @Provides
   @Singleton
   @ELB
   protected URI provideELBURI(@Named(EC2Constants.PROPERTY_ELB_ENDPOINT) String endpoint) {
      return URI.create(endpoint);
   }

   @Provides
   @Singleton
   @ELB
   Map<String, URI> provideELBRegions() {
      return ImmutableMap.<String, URI> of(Region.US_EAST_1, URI
               .create("https://elasticloadbalancing.us-east-1.amazonaws.com"), Region.US_WEST_1,
               URI.create("https://elasticloadbalancing.us-west-1.amazonaws.com"),
               Region.EU_WEST_1,
               URI.create("https://elasticloadbalancing.eu-west-1.amazonaws.com"),
               Region.AP_SOUTHEAST_1, URI
                        .create("https://elasticloadbalancing.ap-southeast-1.amazonaws.com"));
   }

   @Provides
   @Singleton
   @EC2
   String provideCurrentRegion(@EC2 Map<String, URI> regionMap, @EC2 URI currentUri) {
      ImmutableBiMap<URI, String> map = ImmutableBiMap.<String, URI> builder().putAll(regionMap)
               .build().inverse();
      String region = map.get(currentUri);
      assert region != null : currentUri + " not in " + map;
      return region;
   }

   private RuntimeException regionException = null;

   @Provides
   @Singleton
   @EC2
   Map<String, URI> provideRegions(AvailabilityZoneAndRegionClient client) {
      // http://code.google.com/p/google-guice/issues/detail?id=483
      // guice doesn't remember when singleton providers throw exceptions.
      // in this case, if describeRegions fails, it is called again for
      // each provider method that depends on it. To short-circuit this,
      // we remember the last exception trusting that guice is single-threaded
      if (regionException != null)
         throw regionException;
      try {
         return client.describeRegions();
      } catch (RuntimeException e) {
         this.regionException = e;
         throw e;
      }
   }

   @Provides
   @Singleton
   Map<String, String> provideAvailabilityZoneToRegions(AvailabilityZoneAndRegionClient client,
            @EC2 Map<String, URI> regions) {
      Map<String, String> map = Maps.newHashMap();
      for (String region : regions.keySet()) {
         for (AvailabilityZoneInfo zoneInfo : client.describeAvailabilityZonesInRegion(region)) {
            map.put(zoneInfo.getZone(), region);
         }
      }
      return map;
   }

   @Provides
   @TimeStamp
   protected String provideTimeStamp(final DateService dateService,
            @Named(EC2Constants.PROPERTY_AWS_EXPIREINTERVAL) final int expiration) {
      return dateService.iso8601DateFormat(new Date(System.currentTimeMillis()
               + (expiration * 1000)));
   }

   @Provides
   @Singleton
   RequestSigner provideRequestSigner(FormSigner in) {
      return in;
   }

   @Provides
   @Singleton
   protected AMIAsyncClient provideAMIAsyncClient(RestClientFactory factory) {
      return factory.create(AMIAsyncClient.class);
   }

   @Provides
   @Singleton
   public AMIClient provideAMIClient(AMIAsyncClient client) throws IllegalArgumentException,
            SecurityException, NoSuchMethodException {
      return SyncProxy.create(AMIClient.class, client);
   }

   @Provides
   @Singleton
   protected ElasticIPAddressAsyncClient provideElasticIPAddressAsyncClient(
            RestClientFactory factory) {
      return factory.create(ElasticIPAddressAsyncClient.class);
   }

   @Provides
   @Singleton
   protected ElasticLoadBalancerAsyncClient provideElasticLoadBalancerAsyncClient(
            RestClientFactory factory) {
      return factory.create(ElasticLoadBalancerAsyncClient.class);
   }

   @Provides
   @Singleton
   public ElasticIPAddressClient provideElasticIPAddressClient(ElasticIPAddressAsyncClient client)
            throws IllegalArgumentException, SecurityException, NoSuchMethodException {
      return SyncProxy.create(ElasticIPAddressClient.class, client);
   }

   @Provides
   @Singleton
   protected InstanceAsyncClient provideInstanceAsyncClient(RestClientFactory factory) {
      return factory.create(InstanceAsyncClient.class);
   }

   @Provides
   @Singleton
   public InstanceClient provideInstanceClient(InstanceAsyncClient client)
            throws IllegalArgumentException, SecurityException, NoSuchMethodException {
      return SyncProxy.create(InstanceClient.class, client);
   }

   @Provides
   @Singleton
   protected KeyPairAsyncClient provideKeyPairAsyncClient(RestClientFactory factory) {
      return factory.create(KeyPairAsyncClient.class);
   }

   @Provides
   @Singleton
   public KeyPairClient provideKeyPairClient(KeyPairAsyncClient client)
            throws IllegalArgumentException, SecurityException, NoSuchMethodException {
      return SyncProxy.create(KeyPairClient.class, client);
   }

   @Provides
   @Singleton
   protected SecurityGroupAsyncClient provideSecurityGroupAsyncClient(RestClientFactory factory) {
      return factory.create(SecurityGroupAsyncClient.class);
   }

   @Provides
   @Singleton
   public SecurityGroupClient provideSecurityGroupClient(SecurityGroupAsyncClient client)
            throws IllegalArgumentException, SecurityException, NoSuchMethodException {
      return SyncProxy.create(SecurityGroupClient.class, client);
   }

   @Provides
   @Singleton
   protected MonitoringAsyncClient provideMonitoringAsyncClient(RestClientFactory factory) {
      return factory.create(MonitoringAsyncClient.class);
   }

   @Provides
   @Singleton
   public MonitoringClient provideMonitoringClient(MonitoringAsyncClient client)
            throws IllegalArgumentException, SecurityException, NoSuchMethodException {
      return SyncProxy.create(MonitoringClient.class, client);
   }

   @Provides
   @Singleton
   protected AvailabilityZoneAndRegionAsyncClient provideAvailabilityZoneAndRegionAsyncClient(
            RestClientFactory factory) {
      return factory.create(AvailabilityZoneAndRegionAsyncClient.class);
   }

   @Provides
   @Singleton
   public AvailabilityZoneAndRegionClient provideAvailabilityZoneAndRegionClient(
            AvailabilityZoneAndRegionAsyncClient client) throws IllegalArgumentException,
            SecurityException, NoSuchMethodException {
      return SyncProxy.create(AvailabilityZoneAndRegionClient.class, client);
   }

   @Provides
   @Singleton
   protected ElasticBlockStoreAsyncClient provideElasticBlockStoreAsyncClient(
            RestClientFactory factory) {
      return factory.create(ElasticBlockStoreAsyncClient.class);
   }

   @Provides
   @Singleton
   public ElasticBlockStoreClient provideElasticBlockStoreClient(ElasticBlockStoreAsyncClient client)
            throws IllegalArgumentException, SecurityException, NoSuchMethodException {
      return SyncProxy.create(ElasticBlockStoreClient.class, client);
   }

   @Provides
   @Singleton
   public ElasticLoadBalancerClient provideElasticLoadBalancerClient(
            ElasticLoadBalancerAsyncClient client) throws IllegalArgumentException,
            SecurityException, NoSuchMethodException {
      return SyncProxy.create(ElasticLoadBalancerClient.class, client);
   }

   @Provides
   @Singleton
   @EC2
   protected URI provideURI(@Named(EC2Constants.PROPERTY_EC2_ENDPOINT) String endpoint) {
      return URI.create(endpoint);
   }

   protected void bindErrorHandlers() {
      bind(HttpErrorHandler.class).annotatedWith(Redirection.class).to(
               ParseAWSErrorFromXmlContent.class);
      bind(HttpErrorHandler.class).annotatedWith(ClientError.class).to(
               ParseAWSErrorFromXmlContent.class);
      bind(HttpErrorHandler.class).annotatedWith(ServerError.class).to(
               ParseAWSErrorFromXmlContent.class);
   }

   protected void bindRetryHandlers() {
      bind(HttpRetryHandler.class).annotatedWith(Redirection.class).to(
               AWSRedirectionRetryHandler.class);
      bind(HttpRetryHandler.class).annotatedWith(ClientError.class).to(
               AWSClientErrorRetryHandler.class);
   }
}