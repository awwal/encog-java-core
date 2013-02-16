/*
 * Encog(tm) Core v3.2 - Java Version
 * http://www.heatonresearch.com/encog/
 * http://code.google.com/p/encog-java/
 
 * Copyright 2008-2012 Heaton Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *   
 * For more information on Heaton Research copyrights, licenses 
 * and trademarks visit:
 * http://www.heatonresearch.com/copyright
 */
package org.encog.neural.neat.training;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.encog.EncogError;
import org.encog.ml.CalculateScore;
import org.encog.ml.MLMethod;
import org.encog.ml.TrainingImplementationType;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.ea.genome.Genome;
import org.encog.ml.ea.opp.EvolutionaryOperator;
import org.encog.ml.ea.score.AdjustScore;
import org.encog.ml.ea.score.parallel.ParallelScore;
import org.encog.ml.ea.sort.MinimizeAdjustedScoreComp;
import org.encog.ml.ea.sort.MinimizeScoreComp;
import org.encog.ml.ea.train.basic.BasicEA;
import org.encog.ml.genetic.GeneticError;
import org.encog.ml.train.MLTrain;
import org.encog.ml.train.strategy.Strategy;
import org.encog.neural.hyperneat.HyperNEATCODEC;
import org.encog.neural.neat.NEATCODEC;
import org.encog.neural.neat.NEATGenomeFactory;
import org.encog.neural.neat.NEATPopulation;
import org.encog.neural.neat.NEATSpecies;
import org.encog.neural.neat.training.opp.NEATCrossover;
import org.encog.neural.neat.training.opp.NEATMutateAddLink;
import org.encog.neural.neat.training.opp.NEATMutateAddNode;
import org.encog.neural.neat.training.opp.NEATMutateRemoveLink;
import org.encog.neural.neat.training.opp.NEATMutateWeights;
import org.encog.neural.neat.training.species.OriginalNEATSpeciation;
import org.encog.neural.neat.training.species.Speciation;
import org.encog.neural.networks.training.TrainingError;
import org.encog.neural.networks.training.propagation.TrainingContinuation;
import org.encog.util.concurrency.MultiThreadable;

/**
 * Implements NEAT genetic training.
 * 
 * NeuroEvolution of Augmenting Topologies (NEAT) is a genetic algorithm for the
 * generation of evolving artificial neural networks. It was developed by Ken
 * Stanley while at The University of Texas at Austin.
 * 
 * http://www.cs.ucf.edu/~kstanley/
 * 
 */
public class NEATTraining extends BasicEA implements MLTrain, MultiThreadable {

	/**
	 * The best ever network.
	 */
	private NEATGenome bestGenome;

	/**
	 * The number of inputs.
	 */
	private final int inputCount;

	/**
	 * The number of output neurons.
	 */
	private final int outputCount;

	/**
	 * The iteration number.
	 */
	private int iteration;

	private Speciation speciation;
	private List<NEATGenome> newPopulation = new ArrayList<NEATGenome>();
	private int threadCount;
	private int actualThreadCount = -1;
	private int maxTries = 5;
	/**
	 * The probability of each individual link gene being mutated.
	 */
	private double probMutate = 0.5;
	
	/**
	 * The probability of each mutated link gene being assigned a totally new weight.
	 */
	private double probNewWeight = 0.5;
	
	/**
	 * The maximum amount by which a mutation will change the weight.
	 */
	private double maxPertubation = 0.5;
	private EvolutionaryOperator champMutation;
	private Throwable reportedError;
	private NEATGenome oldBestGenome;

	/**
	 * Construct a neat trainer with a new population. The new population is
	 * created from the specified parameters.
	 * 
	 * @param calculateScore
	 *            The score calculation object.
	 * @param inputCount
	 *            The input neuron count.
	 * @param outputCount
	 *            The output neuron count.
	 * @param populationSize
	 *            The population size.
	 */
	public NEATTraining(final CalculateScore calculateScore,
			final int inputCount, final int outputCount,
			final int populationSize) {
		super(new NEATPopulation(inputCount, outputCount, populationSize),
				calculateScore);

		getNEATPopulation().reset();
		this.inputCount = inputCount;
		this.outputCount = outputCount;

		setBestComparator(new MinimizeScoreComp());
		setSelectionComparator(new MinimizeAdjustedScoreComp());

		init();

	}

	/**
	 * Construct neat training with an existing population.
	 * 
	 * @param calculateScore
	 *            The score object to use.
	 * @param population
	 *            The population to use.
	 */
	public NEATTraining(final CalculateScore calculateScore,
			final NEATPopulation population) {
		super(population, calculateScore);

		if (population.size() < 1) {
			throw new TrainingError("Population can not be empty.");
		}

		final NEATGenome genome = (NEATGenome) population.getGenomes().get(0);
		setPopulation(population);
		this.inputCount = genome.getInputCount();
		this.outputCount = genome.getOutputCount();
		init();
	}

	/**
	 * Not supported, will throw an error.
	 * 
	 * @param strategy
	 *            Not used.
	 */
	@Override
	public void addStrategy(final Strategy strategy) {
		throw new TrainingError(
				"Strategies are not supported by this training method.");
	}

	@Override
	public boolean canContinue() {
		return false;
	}

	/**
	 * Called when training is done.
	 */
	@Override
	public void finishTraining() {
		sortPopulation();
	}

	/**
	 * return The error for the best genome.
	 */
	@Override
	public double getError() {
		if (this.bestGenome != null) {
			return this.bestGenome.getScore();
		} else {
			if (this.getScoreFunction().shouldMinimize()) {
				return Double.POSITIVE_INFINITY;
			} else {
				return Double.NEGATIVE_INFINITY;
			}
		}
	}

	@Override
	public TrainingImplementationType getImplementationType() {
		return TrainingImplementationType.Iterative;
	}

	/**
	 * @return The innovations.
	 */
	public NEATInnovationList getInnovations() {
		return (NEATInnovationList) ((NEATPopulation) getPopulation())
				.getInnovations();
	}

	/**
	 * @return The input count.
	 */
	public int getInputCount() {
		return this.inputCount;
	}

	@Override
	public int getIteration() {
		return this.iteration;
	}

	/**
	 * @return A network created for the best genome.
	 */
	@Override
	public MLMethod getMethod() {
		if (this.bestGenome != null) {
			return this.getCODEC().decode(this.bestGenome);
		} else {
			return null;
		}
	}

	/**
	 * @return The number of output neurons.
	 */
	public int getOutputCount() {
		return this.outputCount;
	}

	/**
	 * Returns an empty list, strategies are not supported.
	 * 
	 * @return The strategies in use(none).
	 */
	@Override
	public List<Strategy> getStrategies() {
		return new ArrayList<Strategy>();
	}

	/**
	 * Returns null, does not use a training set, rather uses a score function.
	 * 
	 * @return null, not used.
	 */
	@Override
	public MLDataSet getTraining() {
		return null;
	}

	/**
	 * setup for training.
	 */
	private void init() {
		this.speciation = new OriginalNEATSpeciation();

		this.champMutation = new NEATMutateWeights();
		addOperation(0.5, new NEATCrossover());
		addOperation(0.494, this.champMutation);
		addOperation(0.0005, new NEATMutateAddNode());
		addOperation(0.005, new NEATMutateAddLink());
		addOperation(0.0005, new NEATMutateRemoveLink());
		this.getOperators().finalizeStructure();

		if (this.getNEATPopulation().isHyperNEAT()) {
			setCODEC(new HyperNEATCODEC());
		} else {
			setCODEC(new NEATCODEC());
		}

		// check the population
		for (final Genome obj : getPopulation().getGenomes()) {
			if (!(obj instanceof NEATGenome)) {
				throw new TrainingError(
						"Population can only contain objects of NEATGenome.");
			}

			final NEATGenome neat = (NEATGenome) obj;

			if ((neat.getInputCount() != this.inputCount)
					|| (neat.getOutputCount() != this.outputCount)) {
				throw new TrainingError(
						"All NEATGenome's must have the same input and output sizes as the base network.");
			}
		}
	}

	private void preIteration() {

		this.speciation.init(this);

		// find out how many threads to use
		if (this.threadCount == 0) {
			this.actualThreadCount = Runtime.getRuntime().availableProcessors();
		} else {
			this.actualThreadCount = this.threadCount;
		}

		// score the initial population
		ParallelScore pscore = new ParallelScore(getPopulation(),
				this.getCODEC(), new ArrayList<AdjustScore>(),
				this.getScoreFunction(), inputCount);
		pscore.setThreadCount(this.actualThreadCount);
		pscore.process();
		this.actualThreadCount = pscore.getThreadCount();
		
		// just pick the first genome as best, it will be updated later.
		// also most populations are sorted this way after training finishes (for reload)
		this.bestGenome = (NEATGenome) this.getNEATPopulation().getGenomes().get(0);

		// speciate
		this.speciation.performSpeciation();

	}

	/**
	 * @return True if training can progress no further.
	 */
	@Override
	public boolean isTrainingDone() {
		return false;
	}

	/**
	 * Perform one training iteration.
	 */
	@Override
	public void iteration() {
		if (this.actualThreadCount == -1) {
			preIteration();
		}

		this.iteration++;

		ExecutorService taskExecutor = null;

		if (this.actualThreadCount == 1) {
			taskExecutor = Executors.newSingleThreadScheduledExecutor();
		} else {
			taskExecutor = Executors.newFixedThreadPool(this.actualThreadCount);
		}

		// Clear new population to just best genome.
		newPopulation.clear();
		this.newPopulation.add(this.bestGenome);
		this.oldBestGenome = this.bestGenome;
		
		// execute species in parallel
		for (final NEATSpecies s : ((NEATPopulation) getPopulation())
				.getSpecies()) {
			NEATTrainWorker worker = new NEATTrainWorker(this, s);
			taskExecutor.execute(worker);
		}

		// wait for threadpool to shutdown
		taskExecutor.shutdown();
		try {
			taskExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			throw new GeneticError(e);
		}
		
		if( this.reportedError!=null ) {
			throw new GeneticError(this.reportedError);
		}

		int champRange = Math.min(this.getPopulation().size(), 10);

		Random rnd = new Random();

		while (newPopulation.size() < getNEATPopulation().getPopulationSize()) {
			int index = rnd.nextInt(champRange);
			NEATGenome sel = ((NEATGenomeFactory) getPopulation()
					.getGenomeFactory()).factor((NEATGenome) getPopulation()
					.get(index));
			NEATGenome[] parent = { sel };
			parent[0].setGenomeID(getNEATPopulation().assignGenomeID());
			parent[0].setBirthGeneration(getIteration());
			this.champMutation.performOperation(rnd, parent, 0, parent, 0);
			this.addChild(parent[0]);
		}

		getPopulation().clear();
		getPopulation().addAll(newPopulation);
		
		if( isValidationMode() ) {
			int currentPopSize = this.getPopulation().getGenomes().size();
			int targetPopSize = this.getNEATPopulation().getPopulationSize();
			if( currentPopSize != targetPopSize) {
				throw new EncogError("Population size of "+currentPopSize+" is outside of the target size of " + targetPopSize);
			}
			
			
			if( this.oldBestGenome!=null && 
					!this.getPopulation().getGenomes().contains(this.oldBestGenome)) {
				throw new EncogError("The top genome died, this should never happen!!");
			}
			
			if (this.bestGenome != null
					&& this.oldBestGenome != null
					&& this.getBestComparator().isBetterThan(
							this.oldBestGenome, this.bestGenome)) {
				throw new EncogError(
						"The best genome's score got worse, this should never happen!! Went from "
								+ this.oldBestGenome.getScore() + " to "
								+ this.bestGenome.getScore());
			}
		}

		this.speciation.performSpeciation();
	}

	public boolean addChild(NEATGenome genome) {
		synchronized (this.newPopulation) {
			if (this.newPopulation.size() < this.getPopulation().size()) {
				// don't readd the old best genome, it was already added
				if( genome!=this.oldBestGenome ) {
					
					if( isValidationMode() ) {
						if( this.newPopulation.contains(genome) ) {
							throw new EncogError("Genome already added to population: " + genome.toString());
						}
					}
					
					this.newPopulation.add(genome);
				}
				
				if ( getBestComparator().isBetterThan(genome,this.bestGenome)) {
					this.bestGenome = genome;
				}
				return true;
			} else {
				if( this.isValidationMode() ) {
					//throw new EncogError("Population overflow");
				}
				return false;
			}
		}
	}

	/**
	 * Perform the specified number of training iterations. This is a basic
	 * implementation that just calls iteration the specified number of times.
	 * However, some training methods, particularly with the GPU, benefit
	 * greatly by calling with higher numbers than 1.
	 * 
	 * @param count
	 *            The number of training iterations.
	 */
	@Override
	public void iteration(final int count) {
		for (int i = 0; i < count; i++) {
			iteration();
		}
	}

	@Override
	public TrainingContinuation pause() {
		return null;
	}

	@Override
	public void resume(final TrainingContinuation state) {

	}

	/**
	 * Not used.
	 * 
	 * @param error
	 *            Not used.
	 */
	@Override
	public void setError(final double error) {
	}

	@Override
	public void setIteration(final int iteration) {
		this.iteration = iteration;
	}

	/**
	 * Sort the genomes.
	 */
	public void sortPopulation() {
		getPopulation().sort(this.getBestComparator());
	}

	public NEATPopulation getNEATPopulation() {
		return (NEATPopulation) getPopulation();
	}

	@Override
	public int getThreadCount() {
		return this.threadCount;
	}

	@Override
	public void setThreadCount(int numThreads) {
		this.threadCount = numThreads;
	}

	/**
	 * @return the maxTries
	 */
	public int getMaxTries() {
		return maxTries;
	}

	/**
	 * @param maxTries
	 *            the maxTries to set
	 */
	public void setMaxTries(int maxTries) {
		this.maxTries = maxTries;
	}


	/**
	 * @return the maxPertubation
	 */
	public double getMaxPertubation() {
		return maxPertubation;
	}

	/**
	 * @param maxPertubation
	 *            the maxPertubation to set
	 */
	public void setMaxPertubation(double maxPertubation) {
		this.maxPertubation = maxPertubation;
	}

	/**
	 * @return the speciation
	 */
	public Speciation getSpeciation() {
		return speciation;
	}

	/**
	 * @param speciation
	 *            the speciation to set
	 */
	public void setSpeciation(Speciation speciation) {
		this.speciation = speciation;
	}

	/**
	 * @return the bestGenome
	 */
	public NEATGenome getBestGenome() {
		return bestGenome;
	}

	public void reportError(Throwable t) {
		synchronized(this) {
			if( this.reportedError==null ) {
				this.reportedError = t;
			}
		}
	}
	
	public void dump(File file) {
		try {
			FileOutputStream fos = new FileOutputStream(file);
			dump(fos);
			fos.close();
		} catch(IOException ex) {
			throw new GeneticError(ex);
		}
	}
	
	public void dump(OutputStream os) {
		this.sortPopulation();
		
		PrintWriter out = new PrintWriter(new OutputStreamWriter(os));
				
		if( this.bestGenome!=null ) {
			out.println("Best genome: " + this.bestGenome.toString());
		}
		
		
		out.println("Species");
		for(int i=0;i<this.getNEATPopulation().getSpecies().size();i++) {
			NEATSpecies species = this.getNEATPopulation().getSpecies().get(i);
			out.println("Species #" + i + ":" + species.toString());
		}
		
		out.println("Species Detail");
		for(int i=0;i<this.getNEATPopulation().getSpecies().size();i++) {
			NEATSpecies species = this.getNEATPopulation().getSpecies().get(i);
			out.println("Species #" + i + ":" + species.toString());
			out.println("Leader:" + species.getLeader()); 
			for(int j=0;j<species.getMembers().size();j++) {
				out.println("Species Member #" + j + ":" + species.getMembers().get(j));
			}
		}
		
		out.println("Population Dump");
		for(int i=0;i<this.getNEATPopulation().getGenomes().size();i++) {
			out.println("Genome #" + i + ":" + this.getNEATPopulation().getGenomes().get(i));
		}
		
		out.flush();
				
	}

	/**
	 * @return the probMutate
	 */
	public double getProbMutate() {
		return probMutate;
	}

	/**
	 * @param probMutate the probMutate to set
	 */
	public void setProbMutate(double probMutate) {
		this.probMutate = probMutate;
	}

	/**
	 * @return the probNewWeight
	 */
	public double getProbNewWeight() {
		return probNewWeight;
	}

	/**
	 * @param probNewWeight the probNewWeight to set
	 */
	public void setProbNewWeight(double probNewWeight) {
		this.probNewWeight = probNewWeight;
	}

	/**
	 * @return the oldBestGenome
	 */
	public NEATGenome getOldBestGenome() {
		return oldBestGenome;
	}
	
	

}
