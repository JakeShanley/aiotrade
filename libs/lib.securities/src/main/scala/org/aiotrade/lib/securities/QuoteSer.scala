/*
 * Copyright (c) 2006-2007, AIOTrade Computing Co. and Contributors
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 *  o Redistributions of source code must retain the above copyright notice, 
 *    this list of conditions and the following disclaimer. 
 *    
 *  o Redistributions in binary form must reproduce the above copyright notice, 
 *    this list of conditions and the following disclaimer in the documentation 
 *    and/or other materials provided with the distribution. 
 *    
 *  o Neither the name of AIOTrade Computing Co. nor the names of 
 *    its contributors may be used to endorse or promote products derived 
 *    from this software without specific prior written permission. 
 *    
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR 
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR 
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, 
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; 
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR 
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, 
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.aiotrade.lib.securities

import java.util.logging.Logger
import org.aiotrade.lib.collection.ArrayList
import org.aiotrade.lib.math.indicator.Plot
import org.aiotrade.lib.math.timeseries.{DefaultBaseTSer, TFreq, TSerEvent, TVal}
import org.aiotrade.lib.securities.model.Exchanges
import org.aiotrade.lib.securities.model.Quote
import org.aiotrade.lib.securities.model.Sec
import org.aiotrade.lib.util.reactors.Reactions
import scala.collection.mutable

/**
 *
 * @author Caoyuan Deng
 */
class QuoteSer(_sec: Sec, _freq: TFreq) extends DefaultBaseTSer(_sec, _freq) {
  private val log = Logger.getLogger(this.getClass.getName)
  
  private var _shortName: String = _sec.uniSymbol
  var adjusted: Boolean = false
    
  val open   = TVar[Double]("O", Plot.Quote)
  val high   = TVar[Double]("H", Plot.Quote)
  val low    = TVar[Double]("L", Plot.Quote)
  val close  = TVar[Double]("C", Plot.Quote)
  val volume = TVar[Double]("V", Plot.Volume)
  val amount = TVar[Double]("A", Plot.Volume)
    
  // unadjusted values
  val open_ori  = TVar[Double]("O")
  val high_ori  = TVar[Double]("H")
  val low_ori   = TVar[Double]("L")
  val close_ori = TVar[Double]("C")

  val isClosed = TVar[Boolean]()
  
  private val exportVars = List(open_ori, high_ori, low_ori, close_ori, volume, amount)

  override def serProvider: Sec = super.serProvider.asInstanceOf[Sec]

  override protected def assignValue(tval: TVal) {
    val time = tval.time
    tval match {
      case quote: Quote =>
        open(time)   = quote.open
        high(time)   = quote.high
        low(time)    = quote.low
        close(time)  = quote.close
        volume(time) = quote.volume
        amount(time) = quote.amount

        open_ori(time)  = quote.open
        high_ori(time)  = quote.high
        low_ori(time)   = quote.low
        close_ori(time) = quote.close

        isClosed(time) = quote.closed_?
      case _ => assert(false, "Should pass a Quote type TimeValue")
    }
  }

  def valueOf(time: Long): Option[Quote] = {
    if (exists(time)) {
      val quote = new Quote
      quote.open   = open(time)
      quote.high   = high(time)
      quote.low    = low(time)
      quote.close  = close(time)
      quote.volume = volume(time)
      quote.amount = amount(time)
      if (isClosed(time)) quote.closed_! else quote.unclosed_!
      
      Some(quote)
    } else None
  }

  /**
   * Try to update today's quote item according to quote, if it does not
   * exist, create a new one.
   */
  def updateFrom(quote: Quote) {
    val time = quote.time
    createOrClear(time)

    assignValue(quote)

    /** be ware of fromTime here may not be same as ticker's event */
    publish(TSerEvent.Updated(this, "", time, time))
  }

  def adjust(b: Boolean) {
    if (isLoaded) {
      doAdjust(b)
    } else {
      // to avoid forward reference when "reactions -= reaction", we have to define 'reaction' first
      var reaction: Reactions.Reaction = null
      reaction = {
        case TSerEvent.Loaded(ser: QuoteSer, uniSymbol, frTime, toTime, _, _) if ser eq this =>
          reactions -= reaction
          doAdjust(b)
      }
      reactions += reaction

      // TSerEvent.Loaded may have missed during above produre, so confirm it
      if (isLoaded) {
        reactions -= reaction
        doAdjust(b)
      }
    }
  }

  /**
   * @param boolean b: if true, do adjust, else, de adjust
   */
  private def doAdjust(b: Boolean) {
    if (adjusted && b || !adjusted && !b) return
    
    val divs = Exchanges.dividendsOf(serProvider)
    if (divs.isEmpty) {
      adjusted = b
      return
    }
    
    var i = -1
    while ({i += 1; i < size}) {
      val time = timestamps(i)

      var h = high(i)
      var l = low(i)
      var o = open(i)
      var c = close(i)

      val divItr = divs.iterator
      while (divItr.hasNext) {
        val div = divItr.next
        if (time < div.dividendDate) {
          if (b) {
            h = div.adjust(h)
            l = div.adjust(l)
            o = div.adjust(o)
            c = div.adjust(c)
          } else {
            h = div.unadjust(h)
            l = div.unadjust(l)
            o = div.unadjust(o)
            c = div.unadjust(c)
          }
          
          high (i) = h
          low  (i) = l
          open (i) = o
          close(i) = c
        }
      }
    }

    adjusted = b
    
    log.info(if (adjusted) "Adjusted!" else "Unadjusted!")
        
    publish(TSerEvent.Updated(this, null, 0, lastOccurredTime))
  }

  override def export(fromTime: Long, toTime: Long): collection.Map[String, Any] = {
    try {
      readLock.lock
      timestamps.readLock.lock

      val frIdx = timestamps.indexOfNearestOccurredTimeBehind(fromTime)
      var toIdx = timestamps.indexOfNearestOccurredTimeBefore(toTime)
      toIdx = exportVars.foldLeft(toIdx){(acc, v) => math.min(acc, v.values.length)}
      val len = toIdx - frIdx + 1
      
      if (frIdx >= 0 && toIdx >= 0 && toIdx >= frIdx) {
        val vmap = new mutable.HashMap[String, Array[_]]()

        val times = timestamps.sliceToArray(frIdx, len)
        vmap.put(".", times)

        for (v <- exportVars) {
          val values = v.values.sliceToArray(frIdx, len)
          vmap.put(v.name, values)
        }

        vmap
      } else {
        Map()
      }

    } finally {
      timestamps.readLock.unlock
      readLock.unlock
    }
  }
  
  override def shortName: String = {
    if (adjusted) {
      _shortName + "(*)"
    } else {
      _shortName
    }
  }

}

object QuoteSer {
  private val log = Logger.getLogger(this.getClass.getName)
  
  def importFrom(vmap: collection.Map[String, Array[_]]): Array[Quote] = {
    val quotes = new ArrayList[Quote]()
    try {
      val times   = vmap(".")
      val opens   = vmap("O")
      val highs   = vmap("H")
      val lows    = vmap("L")
      val closes  = vmap("C")
      val volumes = vmap("V")
      val amounts = vmap("A")
    
      var i = -1
      while ({i += 1; i < times.length}) {
        // the time should be properly set to 00:00 of exchange location's local time, i.e. rounded to TFreq.DAILY
        val time = times(i).asInstanceOf[Long]
        val quote = new Quote

        quote.time   = time
        quote.open   = opens(i).asInstanceOf[Double]
        quote.high   = highs(i).asInstanceOf[Double]
        quote.low    = lows(i).asInstanceOf[Double]
        quote.close  = closes(i).asInstanceOf[Double]
        quote.volume = volumes(i).asInstanceOf[Double]
        quote.amount = amounts(i).asInstanceOf[Double]

        if (quote.high * quote.low * quote.close == 0) {
          // bad quote, do nothing
        } else {
          quotes += quote
        }
      }
    } catch {
      case ex => log.warning(ex.getMessage)
    }

    quotes.toArray
  }
  
}



