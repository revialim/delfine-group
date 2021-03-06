package de.sb.broker.rest;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.persistence.Cache;
//import java.util.Set;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.RollbackException;
import javax.persistence.TypedQuery;
import javax.validation.ConstraintViolationException;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.Consumes;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response.Status;

import de.sb.broker.model.Auction;
import de.sb.broker.model.Bid;
import de.sb.broker.model.Person;

@Path("/auctions")
public class AuctionService {
	/*
	 * GET /auctions: Returns the auctions matching the given criteria, 
	 * with null or missing parameters identifying omitted criteria.
	 * ---
	 * no path needed since the one we are referencing 
	 * was set before the class definition
	 */	
	@GET
	public List<Auction> getAuctions(
			@QueryParam("title") String title, 
			@QueryParam("minUC") Short minUnitCount, 
			@QueryParam("maxUC") Short maxUnitCount,
			@QueryParam("minAP") Long minAskingPrice,
			@QueryParam("maxAP") Long maxAskingPrice,
			@QueryParam("maxClosureTimestamp") Long maxClosureTimestamp,
			@QueryParam("minClosureTimestamp") Long minClosureTimestamp,
			@QueryParam("description") String description,
			@QueryParam("firstResult") int firstResult,
			@QueryParam("maxResults") int maxResults,
			@QueryParam("minCreationTimestamp") long minCreationTimestamp,
			@QueryParam("maxCreationTimestamp") long maxCreationTimestamp,
			@HeaderParam("Authorization") final String authentication
			){
		LifeCycleProvider.authenticate(authentication);
		EntityManager em = LifeCycleProvider.brokerManager();
		
		TypedQuery<Long> qa = em.createQuery("select a.identity from Auction as a where"
				+ "(:title is null or a.title = :title) and"
				+ "(:minUC is null or a.unitCount >= :minUC) and"
				+ "(:maxUC is null or a.unitCount <= :maxUC) and"
				+ "(:minAP is null or a.askingPrice >= :minAP) and"
				+ "(:maxAP is null or a.askingPrice <= :maxAP) and"
				+ "(:maxClosureTimestamp is null or a.closureTimestamp <= :maxClosureTimestamp) and"
				+ "(:minClosureTimestamp is null or a.closureTimestamp <= :minClosureTimestamp) and"
				+ "(:maxCreationTimestamp is null or a.creationTimestamp <= :maxCreationTimestamp) and"
				+ "(:minCreationTimestamp is null or a.creationTimestamp <= :minCreationTimestamp) and"
				+ "(:description is null or a.description = :description)", 
				Long.class);
		
		qa.setParameter("title", title);
		qa.setParameter("minUC", minUnitCount);
		qa.setParameter("maxUC", maxUnitCount);
		qa.setParameter("minAP", minAskingPrice);
		qa.setParameter("maxAP", maxAskingPrice);
		qa.setParameter("maxClosureTimestamp", maxClosureTimestamp);
		qa.setParameter("minClosureTimestamp", minClosureTimestamp);
		qa.setParameter("minCreationTimestamp", minCreationTimestamp);
		qa.setParameter("maxCreationTimestamp", maxCreationTimestamp);
		qa.setParameter("description", description);
		if(maxResults > 0) qa.setMaxResults(maxResults);		
		if(firstResult > 0) qa.setFirstResult(firstResult);
		
		List<Long> auctionIds = qa.getResultList();
		List<Auction> auctions = new ArrayList<Auction>();
		
		//for-Schleife über alle IDs
		for(Long id : auctionIds){
			auctions.add(em.find(Auction.class, id));
		}
		
		//sort by close date
		//auctions.sort((a,b) -> Long.compare(a.getClosureTimestamp(),b.getClosureTimestamp()));
		auctions.sort( Comparator
				.comparing(Auction::getClosureTimestamp)
				.thenComparing(Auction::getCreationTimestamp)
				.thenComparing(Auction::getIdentity));
		
		return auctions;
	}
	/*
	* PUT /auctions: Creates or modifies an auction from the given template data. 
	* Note that an auction may only be modified as long as it is not sealed 
	* (i.e. is open and still without bids).
	*
	* PUT /auctions (Aufg 4): 
	* Additionally constrain that an auction may only be altered 
	* if the requester is the same as the auction's seller.
	*/
	@PUT
	@Consumes({"application/xml", "application/json"})
	public Long putAuctions(
			@Valid Auction template,
			@HeaderParam("Authorization") final String authentication
			){
		EntityManager em = LifeCycleProvider.brokerManager();
		final Person requester = LifeCycleProvider.authenticate(authentication);
		final boolean persist = template.getIdentity() == 0;
		final Auction auction;
				
		try {
			//final boolean insert = template.getIdentity() == 0;
						
			//Frage: Wie Zugriff auf alle Auctions
			
			//Verwendung? cache = em.getEntityManagerFactory().getCache();
			//cache.evict(entity.getClass(), entity.getIdentity());
			
			if(persist){
				auction = new Auction(requester);
			} else {
				auction = em.find(Auction.class, template.getIdentity());
				if (auction == null) throw new NotFoundException();
				if (requester.getIdentity() != auction.getSellerReference()) throw new ForbiddenException();
				if (auction.isSealed()) throw new ForbiddenException();
			}
			
			auction.setTitle(template.getTitle());
			auction.setDescription(template.getDescription());
			auction.setClosureTimestamp(template.getClosureTimestamp());
			auction.setAskingPrice(template.getAskingPrice());
			auction.setUnitCount(template.getUnitCount());
			auction.setVersion(template.getVersion());
			
		
			if (persist){
				em.persist(auction);	
			} else {
				em.flush();
			}
			
			try {
				em.getTransaction().commit();
			} finally {
				em.getTransaction().begin();
			}
			
		} catch (ConstraintViolationException e) {
			throw new ClientErrorException(Status.BAD_REQUEST);
		} catch (RollbackException e) {
			throw new ClientErrorException(Status.CONFLICT);
		}
		
		return auction.getIdentity();
	}

	/*
	 * GET /auctions/{identity}: Returns the auction matching the given identity
	 */
	@GET
	@Path("/{identity}")
	public Auction getAuction(@PathParam("identity") Long identity, @HeaderParam("Authorization") final String authentication){
		LifeCycleProvider.authenticate(authentication);
		EntityManager em = LifeCycleProvider.brokerManager();
		
		Auction auction = em.find(Auction.class, identity);
		
		return auction;
	}
	
	/*
	 * GET /auctions/{identity}/bid (new): 
	 * Returns the requester's bid for the given auction, or null if none exists.
	 */
	@GET
	@Path("/{identity}/bid")
	@Produces("text/plain")
	@Bid.XmlBidderAsReferenceFilter
	@Bid.XmlAuctionAsReferenceFilter
	public long getBidForAuction(
			@PathParam("identity") long identity,
			@HeaderParam("Authorization") final String authentication
			) {
		
		final Person bidder = LifeCycleProvider.authenticate(authentication);
		
		for (Bid bid : bidder.getBids()) {
			if (bid.getAuction().getIdentity() == identity){
				return bid.getPrice();
			}
		}
		return -1;
	}
	
	/*
	 * POST /auctions/{identity}/bid (new): 
	 * Creates or modifies the requester's bid for the given auction, 
	 * depending on the requester and the price (in cent) within the given request body. 
	 * If the price is zero, then the requester's bid is removed instead.
	 */
	@POST
	@Path("/{identity}/bid")
	@Consumes("text/plain")
	@Produces("text/plain")
	public long CreateUpdateOrDeleteBid(
		@PathParam("identity") long identity,
		@HeaderParam("Authorization") final String authentication,
		@Min(0) long price
		//nicht die ganze bid – @Valid @NotNull Bid template
	) {
		final EntityManager brokerManager = LifeCycleProvider.brokerManager();
		final Person bidder = LifeCycleProvider.authenticate(authentication);
		final Auction auction = brokerManager.find(Auction.class, identity);
		if (auction == null) throw new NotFoundException();
		
		Bid bid = auction.getBid(bidder);
		if (bid == null){
			if (price == 0) return 0;
			bid = new Bid(auction, bidder);
		} 
		
		//wenn price == 0, dann bestehende auktion löschen
		if (price == 0){
			brokerManager.remove(bid);
		}  else {
			bid.setPrice(price);
		}
		
		try {
			if (bid.getIdentity() == 0) {
				brokerManager.persist(bid);
			} else { 
				brokerManager.flush();
			}
		}  catch (ConstraintViolationException e) {
			throw new ClientErrorException(Status.BAD_REQUEST);
		}
		
		try { 
			brokerManager.getTransaction().commit();
		} catch (RollbackException e) {
			throw new ClientErrorException(Status.CONFLICT);
		}  finally {
			brokerManager.getTransaction().begin();
		}
		
		return bid.getIdentity();
	}
}



