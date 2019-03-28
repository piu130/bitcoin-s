package org.bitcoins.rpc.config

import java.io.File
import java.net.URI
import java.nio.file.Paths

import org.bitcoins.rpc.client.common.BitcoindVersion

import scala.sys.process._
import org.bitcoins.core.util.BitcoinSLogger
import org.bitcoins.core.config.NetworkParameters
import scala.util.Try
import java.nio.file.Files

/**
  * Created by chris on 4/29/17.
  */
sealed trait BitcoindInstance extends BitcoinSLogger {

  require(binary.exists,
          s"bitcoind binary path (${binary.getAbsolutePath}) does not exist!")

  // would like to check .canExecute as well, but we've run into issues on some machines
  require(binary.isFile,
          s"bitcoind binary path (${binary.getAbsolutePath}) must be a file")

  /** The binary file that should get executed to start Bitcoin Core */
  def binary: File

  def datadir: File

  def network: NetworkParameters
  def uri: URI
  def rpcUri: URI
  def authCredentials: BitcoindAuthCredentials
  def zmqConfig: ZmqConfig

  def getVersion: BitcoindVersion = {

    val binaryPath = binary.getAbsolutePath
    val foundVersion = Seq(binaryPath, "--version").!!.split("\n").head
      .split(" ")
      .last

    foundVersion match {
      case _: String if foundVersion.startsWith(BitcoindVersion.V16.toString) =>
        BitcoindVersion.V16
      case _: String if foundVersion.startsWith(BitcoindVersion.V17.toString) =>
        BitcoindVersion.V17
      case _: String => BitcoindVersion.Unknown
    }
  }
}

object BitcoindInstance {
  private case class BitcoindInstanceImpl(
      network: NetworkParameters,
      uri: URI,
      rpcUri: URI,
      authCredentials: BitcoindAuthCredentials,
      zmqConfig: ZmqConfig,
      binary: File,
      datadir: File
  ) extends BitcoindInstance

  def apply(
      network: NetworkParameters,
      uri: URI,
      rpcUri: URI,
      authCredentials: BitcoindAuthCredentials,
      zmqConfig: ZmqConfig = ZmqConfig(),
      binary: File = DEFAULT_BITCOIND_LOCATION,
      datadir: File = BitcoindConfig.DEFAULT_DATADIR
  ): BitcoindInstance = {
    BitcoindInstanceImpl(network,
                         uri,
                         rpcUri,
                         authCredentials,
                         zmqConfig = zmqConfig,
                         binary = binary,
                         datadir = datadir)
  }

  lazy val DEFAULT_BITCOIND_LOCATION: File = {
    val path = Try("which bitcoind".!!)
      .getOrElse(
        throw new RuntimeException("Could not locate bitcoind on user PATH"))
    new File(path.trim)
  }

  /** Constructs a `bitcoind` instance from the given datadir, using the
    * `bitcoin.conf` found within (if any)
    *
    * @throws IllegalArgumentException if the given datadir does not exist
    */
  def fromDatadir(
      datadir: File = BitcoindConfig.DEFAULT_DATADIR): BitcoindInstance = {
    require(datadir.exists, s"${datadir.getPath} does not exist!")
    require(datadir.isDirectory, s"${datadir.getPath} is not a directory!")

    val configPath = Paths.get(datadir.getAbsolutePath, "bitcoin.conf")
    if (Files.exists(configPath)) {

      val file = configPath.toFile()
      fromConfigFile(file)
    } else {
      fromConfig(
        BitcoindConfig.empty.withOption("datadir", configPath.toString))
    }
  }

  /**
    * Construct a `bitcoind` from the given config file. If no `datadir` setting
    * is found, the parent directory to the given file is used.
    *
    * @throws IllegalArgumentException if the given config file does not exist
    */
  def fromConfigFile(
      file: File = BitcoindConfig.DEFAULT_CONF_FILE): BitcoindInstance = {
    require(file.exists, s"${file.getPath} does not exist!")
    require(file.isFile, s"${file.getPath} is not a file!")

    val conf = BitcoindConfig(file)

    val confWithDatadir = if (conf.datadir.isEmpty) {
      conf.withOption("datadir", file.getParent.toString)
    } else {
      conf
    }

    fromConfig(confWithDatadir)
  }

  /** Constructs a `bitcoind` instance from the given config */
  def fromConfig(
      config: BitcoindConfig
  ): BitcoindInstance = {

    val authCredentials = BitcoindAuthCredentials.fromConfig(config)

    config.datadir match {
      case None =>
        BitcoindInstance(config.network,
                         config.uri,
                         config.rpcUri,
                         authCredentials,
                         zmqConfig = ZmqConfig.fromConfig(config))
      case Some(datadir) =>
        BitcoindInstance(config.network,
                         config.uri,
                         config.rpcUri,
                         authCredentials,
                         zmqConfig = ZmqConfig.fromConfig(config),
                         datadir = datadir)
    }
  }
}