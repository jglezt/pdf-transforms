(ns pdf-transforms.tokens.pdf-extractor
  "A collection of low level functions that interop with pdfbox to
  extract the raw data (e.g. text-positions, drawn lines, and bookmarks) from a pdf"
  (:require [pdf-transforms.tokens.char-normalize :as char-norm]
            [clojure.java.io :as io])
  (:import [org.apache.pdfbox.text PDFTextStripper TextPosition]
           (org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline PDOutlineItem)
           (org.apache.pdfbox.pdmodel.interactive.action PDActionGoTo)
           (org.apache.pdfbox.contentstream PDFGraphicsStreamEngine)
           (java.awt.geom Point2D Point2D$Double)
           (org.apache.pdfbox.io RandomAccessBufferedFileInputStream)
           (org.apache.pdfbox.pdfparser PDFParser)
           (org.apache.pdfbox.pdmodel PDDocument PDPage)
           (org.apache.pdfbox.pdmodel.font PDFont PDFontDescriptor)))


(definterface TextStripperData
  (getData []))

(def italic #"[iI][tT][aA][lL][iI][cC]")
(def bold #"[bB][oO][lL][dD]")

(defn in-memory-text-stripper []
  (let [position-data (atom [])]
    (proxy [PDFTextStripper TextStripperData] []
      (processTextPosition [^TextPosition text]
        (do (swap! position-data #(conj %
                                        (let [^PDFont font (.getFont text)
                                              font-name (if font (.getName font))
                                              ^PDFontDescriptor fdesc (if font (.getFontDescriptor font))]
                                          (merge (if (or (some-> fdesc (.isItalic))
                                                         (re-find italic (or font-name "")))
                                                   {:italic? true})
                                                 (if (or (some-> fdesc (.isForceBold))
                                                         (some-> fdesc (.getFontWeight) (>= 700))
                                                         (re-find bold (or font-name "")))
                                                   {:bold? true})
                                            {:text        (char-norm/replace-unicode-chars (.getUnicode text))
                                             :x           (.getXDirAdj text)
                                             :y           (some-> (.getYDirAdj text) (* 100) float Math/round (/ 100.0))
                                             :page-number (proxy-super getCurrentPageNo) ;expensive, could get 35 % speed increase by doing this some other way ...
                                             :f-size      (.getFontSizeInPt text)
                                             :height      (.getHeight text)
                                             :width       (.getWidthDirAdj text)
                                             :font        font-name}))))
            (proxy-super processTextPosition text)))
      (getData ([] @position-data)))))


;break a rectangle into 4 boundary lines
(defn rectangle->bounds [{:keys [x0 x1 y0 y1] :as rect}]
  [(assoc rect :y0 y1)
   (assoc rect :y1 y0)
   (assoc rect :x0 x1)
   (assoc rect :x1 x0)])

(defn in-memory-line-stripper [^PDPage page]
  (let [line-cutoff 20
        position-data (atom [])
        current-position (atom [0 0])
        top-y (-> page .getCropBox .getUpperRightY)]
    (proxy [PDFGraphicsStreamEngine TextStripperData] [page]
      (appendRectangle [p0 p1 p2 p3]
        (when (> (Point2D/distance (.getX p0) (.getY p0) (.getX p2) (.getY p2)) line-cutoff)
          (swap! position-data #(apply conj % (rectangle->bounds {:x0 (.getX p0) :y0 (- top-y (.getY p0)) :x1 (.getX p2) :y1 (- top-y (.getY p2))})))))
      (getCurrentPoint []
        (Point2D$Double. (first @current-position) (second @current-position)))
      (lineTo [x y]
        (when (> (Point2D/distance (first @current-position) (second @current-position) x y) line-cutoff)
          (swap! position-data #(conj % {:x0 (first @current-position) :y0 (- top-y (second @current-position)) :x1 x :y1 (- top-y y)})))
        (reset! current-position [x y]))
      (moveTo [x y]
        (reset! current-position [x y]))
      (getData ([] @position-data)))))

;; Private Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn preprocess-pdf [doc]
  (do
    (.setAllSecurityToBeRemoved doc true)  ;decrypt the document
    (let [acro-form (-> doc .getDocumentCatalog .getAcroForm)]
      (if (not (nil? acro-form)) (.flatten acro-form)))  ;convert editable fields into values
    doc))

(defn- build-position-data [pdf-doc & [[start-page end-page]]]
  (let [stripper (cond-> (in-memory-text-stripper)
                         start-page (doto (.setStartPage start-page))
                         end-page (doto (.setEndPage end-page)))]
    (.getText stripper pdf-doc)
    (.getData stripper)))

(defn- build-line-position-data [pdf-doc & [[start-page end-page]]]
  (doall (map
           (fn [page-no] (let [page (.getPage pdf-doc page-no)
                               stripper (in-memory-line-stripper page)]
                           (.processPage stripper page)
                           (map #(assoc % :page-number (inc page-no)) (.getData stripper))))
           (range (or start-page 0) (or end-page (.getNumberOfPages pdf-doc))))))

(defn bookmark->map [^PDOutlineItem bookmark]
  (assoc
    (if (instance? PDActionGoTo (.getAction bookmark))
      (if-let [dest (-> bookmark .getAction .getDestination)]
        { :page-number (inc (.retrievePageNumber dest))
         :y            (try (max (- (-> dest .getPage .getMediaBox .getHeight)
                         (.getTop dest))
                      0)
                 (catch Exception _ 0))}))
    :name (.getTitle bookmark)))

(defn node-recur
  ([node] (node-recur node false))
  ([node root?] {:node (if root? "root" (bookmark->map node))
                 :children (mapv node-recur (.children node))}))

(defn get-bookmarks [pdf-doc & _]
  (if-let [outline (-> pdf-doc .getDocumentCatalog .getDocumentOutline)]
    (:children (node-recur outline true))))

;; PUBLIC Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn process-pdf-document [process-fn is & pages]
  (with-open [doc (-> is io/input-stream RandomAccessBufferedFileInputStream. PDFParser. (doto (.parse)) .getPDDocument)]
    (apply process-fn (cons (preprocess-pdf doc) pages))))

(def extract-char-positions (partial process-pdf-document build-position-data))
(def extract-line-positions (partial process-pdf-document build-line-position-data))
(def extract-bookmarks (partial process-pdf-document get-bookmarks))