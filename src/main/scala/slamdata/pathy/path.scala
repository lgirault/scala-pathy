package slamdata.pathy

import scalaz._, Scalaz._

sealed trait Path[+B,+T,+S] {
  def isAbsolute: Boolean
  def isRelative = !isAbsolute
}
object Path {
  sealed trait Rel
  sealed trait Abs

  sealed trait File
  sealed trait Dir

  sealed trait Sandboxed
  sealed trait Unsandboxed

  final case class FileName(value: String) extends AnyVal {
    def extension: String = {
      val idx = value.lastIndexOf(".")
      if (idx == -1) "" else value.substring(idx + 1)
    }

    def dropExtension: FileName = {
      val idx = value.lastIndexOf(".")
      if (idx == -1) this else FileName(value.substring(0, idx))
    }

    def changeExtension(f: String => String): FileName =
      FileName(dropExtension.value + "." + f(extension))
  }
  final case class DirName(value: String) extends AnyVal

  // Note: this ADT allows invalid paths, but the exposed functions
  // of the package do not.
  private case object Current extends Path[Nothing,Nothing,Nothing] {
    def isAbsolute = false
  }
  private case object Root extends Path[Nothing,Nothing,Nothing] {
    def isAbsolute = true
  }
  private final case class ParentIn[B,T,S](parent: Path[B,T,S]) extends Path[B,T,S] {
    def isAbsolute = parent.isAbsolute
  }
  private final case class DirIn[B,T,S](parent: Path[B,T,S], name: DirName) extends Path[B,T,S] {
    def isAbsolute = parent.isAbsolute
  }
  private final case class FileIn[B,T,S](parent: Path[B,T,S], name: FileName) extends Path[B,T,S] {
    def isAbsolute = parent.isAbsolute
  }

  type RelFile[S] = Path[Rel, File, S]
  type AbsFile[S] = Path[Abs, File, S]
  type RelDir[S] = Path[Rel, Dir, S]
  type AbsDir[S] = Path[Abs, Dir, S]

  trait Escaper {
    def escape(segment: String): String
  }

  val nonEscaper = new Escaper {
    def escape(segment: String) = ???
  }

  val posixEscaper = new Escaper {
    def escape(segment: String) = ???
  }

  def fileName[B,S](path: Path[B, File, S]): FileName = path match {
    case FileIn(_, name) => name
    case _               => FileName("")
  }
  def file[S](name: String): Path[Rel, File, S] = file1(FileName(name))
  private def file1[S](name: FileName): Path[Rel, File, S] = FileIn(Current, name)

  def dir[S](name: String): Path[Rel, Dir, S] = dir1(DirName(name))
  private def dir1[S](name: DirName): Path[Rel, Dir, S] = DirIn(Current, name)

  def dirName[B,S](path: Path[B, Dir, S]): Option[DirName] = path match {
    case DirIn(_, name) => Some(name)
    case _              => None
  }

  implicit class PathOps[B,T,S](path: Path[B,T,S]) {
    def relativeTo[SS](dir: Path[B, Dir, SS]): Option[Path[Rel, T, SS]] = {
      def go[B,T,S,SS](p1: Path[B,T,S], p2: Path[B,Dir,SS]): Option[Path[Rel,T,SS]] =
        if (identicalPath(p1, p2)) Some(Current)
        else peel(p1) match {
          case None => (p1, p2) match {
            case (Root, Root)       => Some(Current)
            case (Current, Current) => Some(Current)
            case _                  => None
          }
          case Some((p1p, v)) =>
            go(p1p, p2).map(p => p </> v.fold(DirIn(Current, _), FileIn(Current, _)))
          }
      go(canonicalize(path), canonicalize(dir))
    }
  }

  implicit class DirOps[B,S](dir: Path[B, Dir, S]) {
    def </>[T](rel: Path[Rel, T, S]): Path[B, T, S] =
      (dir, rel) match {
        case (Current,        Current) => Current
        case (Root,           Current) => Root
        case (ParentIn(p1),   Current) => ParentIn(p1 </> Current)
        case (FileIn(p1, f1), Current) => FileIn(p1 </> Current, f1)
        case (DirIn(p1, d1),  Current) => DirIn(p1 </> Current, d1)

        // these don't make sense, but cannot exist anyway
        case (Current,        Root) => Current
        case (Root,           Root) => Root
        case (ParentIn(p1),   Root) => ParentIn(p1 </> Current)
        case (FileIn(p1, f1), Root) => FileIn(p1 </> Current, f1)
        case (DirIn(p1, d1),  Root) => DirIn(p1 </> Current, d1)

        case (p1, ParentIn(p2))   => ParentIn(p1 </> p2)
        case (p1, FileIn(p2, f2)) => FileIn(p1 </> p2, f2)
        case (p1, DirIn(p2, d2))  => DirIn(p1 </> p2, d2)
      }

    // NB: scala doesn't cotton to `<..>`
    def <::>[T](rel: Path[Rel, T, S]): Path[B, T, Unsandboxed] =
      parentDir1(dir) </> unsandbox(rel)
  }

  implicit class FileOps[B,S](file: Path[B, File, S]) {
    // NB: scala doesn't cotton to `<.>`
    def <:>(ext: String): Path[B, File, S] =
      renameFile(file, name => name.changeExtension(_ => ext))
  }

  def peel[B,T,S](path: Path[B,T,S]): Option[(Path[B, Dir, S], DirName \/ FileName)] = path match {
    case Current         => None
    case Root            => None
    case p @ ParentIn(_) =>
      val (c, p1) = canonicalize1(p)
      if (c) peel(p1) else None
    case DirIn(p, d)     => Some(unsafeCoerceType(p) -> -\/ (d))
    case FileIn(p, f)    => Some(unsafeCoerceType(p) ->  \/-(f))
  }

  def depth[B,T,S](path: Path[B,T,S]): Int = path match {
    case Current      => 0
    case Root         => 0
    case ParentIn(p)  => depth(p) - 1
    case FileIn(p, _) => depth(p) + 1
    case DirIn(p, _)  => depth(p) + 1
  }

  def parentDir[B,T,S](path: Path[B,T,S]): Option[Path[B,Dir,S]] =
    peel(path).map(_._1)

  def unsandbox[B,T,S](path: Path[B,T,S]): Path[B, T, Unsandboxed] = path match {
    case Current      => Current
    case Root         => Root
    case ParentIn(p)  => ParentIn(unsandbox(p))
    case DirIn(p, d)  => DirIn(unsandbox(p), d)
    case FileIn(p, f) => FileIn(unsandbox(p), f)
  }

  def parentDir1[B,T,S](path: Path[B,T,S]): Path[B, Dir, Unsandboxed] =
    ParentIn(unsafeCoerceType(unsandbox(path)))

  private def unsafeCoerceType[B,T,TT,S](path: Path[B,T,S]): Path[B,TT,S] = path match {
    case Current      => Current
    case Root         => Root
    case ParentIn(p)  => ParentIn(unsafeCoerceType(p))
    case DirIn(p, d)  => DirIn(unsafeCoerceType(p), d)
    case FileIn(p, f) => FileIn(unsafeCoerceType(p), f)
  }

  def currentDir[S]: Path[Rel, Dir, S] = Current
  def rootDir[S]: Path[Abs, Dir, S] = Root

  def renameFile[B,S](path: Path[B, File, S], f: FileName => FileName): Path[B, File, S] =
    path match {
      case FileIn(p, f0) => FileIn(p, f(f0))
      case p             => p
    }

  def renameDir[B,S](path: Path[B, Dir, S], f: DirName => DirName): Path[B, Dir, S] =
    path match {
      case DirIn(p, d) => DirIn(p, f(d))
      case p           => p
    }

  def canonicalize[B,T,S](path: Path[B,T,S]): Path[B,T,S] =
    canonicalize1(path)._2

  private def canonicalize1[B,T,S](path: Path[B,T,S]): (Boolean, Path[B,T,S]) =
    path match {
      case Current                => false -> Current
      case Root                   => false -> Root
      case ParentIn(FileIn(p, f)) => true -> canonicalize1(p)._2
      case ParentIn(DirIn(p, f))  => true -> canonicalize1(p)._2
      case ParentIn(p)            =>
        val (ch, p1) = canonicalize1(p)
        val p2 = ParentIn(p1)
        if (ch) canonicalize1(p2) else ch -> p2  // ???
      case FileIn(p, f)           =>
        val (ch, p1) = canonicalize1(p)
        ch -> FileIn(p1, f)
      case DirIn(p, d)            =>
        val (ch, p1) = canonicalize1(p)
        ch -> DirIn(p1, d)
    }

  private def unsafePrintPath1[B,T,S](path: Path[B,T,S], esc: Escaper): String =
    path match {
      case Current                               => "./"
      case Root                                  => "/"
      case ParentIn(p)                           => unsafePrintPath1(p, esc) ++ "../"
      case DirIn(p @ FileIn(_, _), DirName(d))   => unsafePrintPath1(p, esc) + "/" + d + "/"
      case DirIn(p, DirName(d))                  => unsafePrintPath1(p, esc) + d + "/"
      case FileIn(p @ FileIn(_, _), FileName(f)) => unsafePrintPath1(p, esc) + "/" + f
      case FileIn(p, FileName(f))                => unsafePrintPath1(p, esc) + f
    }

  def unsafePrintPath[B,T,S](path: Path[B,T,S]): String =
    unsafePrintPath1(path, posixEscaper)

  def printPath[B,T](path: Path[B, T, Sandboxed]): String =
    unsafePrintPath(path)

  def identicalPath[B,T,S,BB,TT,SS](p1: Path[B,T,S], p2: Path[BB,TT,SS]): Boolean =
    p1.shows == p2.shows

  /** Synonym for relativeTo, constrained to sandboxed dirs, and with a more evocative name. */
  def sandbox[B,T,S](dir: Path[B, Dir, Sandboxed], path: Path[B,T,S]): Option[Path[Rel,T,Sandboxed]] =
    path relativeTo dir

  private def parsePath[Z](
    rf: RelFile[Unsandboxed] => Z,
    af: AbsFile[Unsandboxed] => Z,
    rd: RelDir[Unsandboxed] => Z,
    ad: AbsDir[Unsandboxed] => Z)(str: String): Z =
  {
    val segs = str.split("/")
    val last = segs.length - 1
    val isAbs = str.startsWith("/")
    val isFile = !str.endsWith("/")
    val tuples = segs.zipWithIndex

    def folder[B,T,S](base: Path[B,T,S], t: (String, Int)): Path[B,T,S] = t match {
      case (".", _)  => base
      case ("", _)   => base
      case ("..", _) => ParentIn(base)
      case (seg, idx)  =>
        if (isFile && idx == last)
          FileIn(base, FileName(seg))
        else
          DirIn(base, DirName(seg))
    }

    if (str == "")
      rd(Current)
    else if (isAbs && isFile)
      af(tuples.foldLeft[AbsFile[Unsandboxed]](Root)(folder))
    else if (isAbs && !isFile)
      ad(tuples.foldLeft[AbsDir[Unsandboxed]](Root)(folder))
    else if (!isAbs && isFile)
      rf(tuples.foldLeft[RelFile[Unsandboxed]](Current)(folder))
    else
      rd(tuples.foldLeft[RelDir[Unsandboxed]](Current)(folder))
  }

  val parseRelFile: String => Option[RelFile[Unsandboxed]] =
    parsePath[Option[RelFile[Unsandboxed]]](Some(_), _ => None, _ => None, _ => None)

  val parseAbsFile: String => Option[AbsFile[Unsandboxed]] =
    parsePath[Option[AbsFile[Unsandboxed]]](_ => None, Some(_), _ => None, _ => None)

  val parseRelDir: String => Option[RelDir[Unsandboxed]] =
    parsePath[Option[RelDir[Unsandboxed]]](_ => None, _ => None, Some(_), _ => None)

  val parseAbsDir: String => Option[AbsDir[Unsandboxed]] =
    parsePath[Option[AbsDir[Unsandboxed]]](_ => None, _ => None, _ => None, Some(_))

  implicit def PathShow[B,T,S]: Show[Path[B,T,S]] = new Show[Path[B,T,S]] {
    override def show(v: Path[B,T,S]) = v match {
      case Current                => "currentDir"
      case Root                   => "rootDir"
      case ParentIn(p)            => "parentDir(" + p.show + ")"
      case FileIn(p, FileName(f)) => p.show + " </> file(" + f.show + ")"
      case DirIn(p, DirName(d))   => p.show + " </> dir(" + d.show + ")"
    }
  }
}